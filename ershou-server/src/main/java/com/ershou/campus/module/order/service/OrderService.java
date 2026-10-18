package com.ershou.campus.module.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.cart.entity.CartItem;
import com.ershou.campus.module.cart.service.CartService;
import com.ershou.campus.module.chat.service.ChatService;
import com.ershou.campus.module.chat.vo.ChatSessionVO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.order.dto.CheckoutDTO;
import com.ershou.campus.module.order.dto.CreateOrderDTO;
import com.ershou.campus.module.order.dto.UpdatePointDTO;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import com.ershou.campus.module.order.mapper.PlatformTradeMapper;
import com.ershou.campus.module.order.vo.OrderVO;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final MeetupOrderMapper orderMapper;
    private final ItemMapper itemMapper;
    /** 平台代卖交易 SQL（条件扣库存/完成收口/取消还库存，见第 2 轮平台代卖设计 v1.1 3.5） */
    private final PlatformTradeMapper platformTradeMapper;
    private final CartService cartService;
    private final TradePointService tradePointService;
    private final UserService userService;
    private final SysUserMapper userMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;
    private final ChatService chatService;
    private final OrderStateRecorder orderStateRecorder;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 面交单状态：1 待卖家确认 / 2 待面交 / 3 已完成 / 4 已拒绝 / 5 已取消 */
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_REJECTED = 4;
    public static final int STATUS_CANCELLED = 5;
    /** 「进行中」= 还没落定的那两种，防重、待办统计都用它 */
    public static final List<Integer> STATUS_ACTIVE = List.of(STATUS_PENDING, STATUS_WAITING);

    /** 卖家接受后商品锁定到"时间窗结束 + 2 小时"；没填时间窗就锁 24 小时 */
    private static final int LOCK_BUFFER_HOURS = 2;
    private static final int LOCK_DEFAULT_HOURS = 24;
    private static final int MAX_SLOT_MINUTES = 180;

    // ---------------------------------------------------------------- 平台代卖（第 2 轮）

    /** 平台官方账号 id（平台代卖单的卖家）；默认 1000，见第 2 轮设计 2.1。平台账号不可登录、不参与信用分 */
    @Value("${ershou.platform.seller-id:1000}")
    private Long platformSellerId = 1000L;

    /** item.seller_type 的两个取值（迁移脚本 sql/migration-platform.sql：1=个人 2=平台） */
    private static final int SELLER_TYPE_PLATFORM = 2;

    /** 平台代卖单的备注：平台单没有"卖家接受"这一步，靠它把平台单和普通面交单区分开（设计 2.3） */
    private static final String PLATFORM_ORDER_REMARK = "平台代卖自提单";

    // ---------------------------------------------------------------- 创建

    /** 购物车合并结算：按卖家分组，每组一张面交单，共享 batchNo */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(Long buyerId, CheckoutDTO dto) {
        // 买家的意向单不含时间窗（由卖家定），所以不校验 dto 里的时间；
        // 面交点同样忽略买家传的值，按每件商品自己的关联点位来。
        List<CartItem> cartItems = cartService.rawByIds(buyerId, dto.getCartIds());
        if (cartItems.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "购物车里没有可结算的商品");
        }
        // 先按商品状态分流。
        //
        // 以前是"边建单边校验"，只要有一条不可面交就抛异常 → **整批回滚**，
        // 用户看到的是"商品已被其他同学约走"，然后一件都结算不了（实测 2 张单变 0 张），
        // 而且失败原因说不清是哪件。现在：可面交的照常结算，不可面交的**逐条回报**给前端。
        List<CartItem> ok = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (CartItem ci : cartItems) {
            Item item = itemMapper.selectById(ci.getItemId());
            String reason = unavailableReason(item, buyerId);
            if (reason != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("cartId", ci.getId());
                s.put("itemId", ci.getItemId());
                s.put("title", item == null ? "已下架的商品" : item.getTitle());
                s.put("reason", reason);
                skipped.add(s);
            } else {
                ok.add(ci);
            }
        }
        if (ok.isEmpty()) {
            throw new BizException(ResultCode.ITEM_LOCKED,
                    "勾选的商品都不可面交了（" + (skipped.isEmpty() ? "" : String.valueOf(skipped.get(0).get("reason"))) + "）");
        }

        String batchNo = genBatchNo();
        List<Map<String, Object>> created = new ArrayList<>();
        List<Long> usedCartIds = new ArrayList<>();
        for (CartItem ci : ok) {
            // 面交点按**每件商品自己的**关联点位来（购物车可能有多件、各自点位不同），
            // 时间窗留空 = 待协商（由卖家定）
            Item item = itemMapper.selectById(ci.getItemId());
            Long pointId = buyerSidePoint(item);
            MeetupOrder order = doCreate(buyerId, ci.getItemId(), ci.getQuantity(),
                    pointId, null, null, dto.getRemark(), batchNo);
            created.add(orderBrief(order));
            usedCartIds.add(ci.getId());
            // 提交后给卖家发私聊提醒（事务提交后执行，失败不影响下单）
            TxHooks.afterCommit(() -> notifySeller(order, pointName(pointId)));
        }
        cartService.removeByIds(usedCartIds);
        log.info("[order][checkout][{}] batch={} 生成 {} 张面交单（跳过 {} 件不可面交）",
                buyerId, batchNo, created.size(), skipped.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orders", created);
        result.put("skipped", skipped);
        return result;
    }

    /** 商品为什么不能约面交；能约返回 null（与 {@link #doCreate} 同一套判断口径） */
    private String unavailableReason(Item item, Long buyerId) {
        if (item == null) {
            return "商品已不存在";
        }
        if (item.getStatus() != null && item.getStatus() == 4) {
            return "商品已售出";
        }
        if (item.getStatus() != null && item.getStatus() == 3) {
            return "商品已被其他同学约走";
        }
        if (item.getStatus() == null || item.getStatus() != 2) {
            return "商品当前不可约面交";
        }
        if (Objects.equals(item.getSellerId(), buyerId)) {
            return "不能约自己发布的商品";
        }
        return null;
    }

    /** 点位名（空点位返回 null，前端显示"待协商"） */
    private String pointName(Long pointId) {
        TradePoint point = pointId == null ? null : tradePointService.getById(pointId);
        return point == null ? null : point.getName();
    }

    /**
     * 买家提交意向时的面交点与时间窗。
     *
     * <p>**买家不决定这两项**（2026-10-01 产品规则）：商品在卖家手上，见面的地点与时间由他定
     * （卖家在「我的面交 → 我卖出的」里设置），买家想改就在私聊里商量。
     * 前端已经不填了，但接口可能被直接调用 —— 所以这里在服务端也**忽略**买家传的值：
     * <ul>
     *   <li>面交点：默认用**商品自己关联的点位**（卖家发布时选的），比空着更有用；</li>
     *   <li>时间窗：一律留空 = 待协商，等卖家定。</li>
     * </ul>
     */
    private Long buyerSidePoint(Item item) {
        return item == null ? null : item.getPreferPointId();
    }

    /** 单品直接约面交 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSingle(Long buyerId, CreateOrderDTO dto) {
        // 注意：不调 validateSlot —— 买家传的时间窗会被忽略，不该因为它的格式问题报错
        Item item = itemMapper.selectById(dto.getItemId());
        Long pointId = buyerSidePoint(item);
        String batchNo = genBatchNo();
        String pointName = pointName(pointId);
        MeetupOrder order = doCreate(buyerId, dto.getItemId(), dto.getQuantity(),
                pointId, null, null, dto.getRemark(), batchNo);
        TxHooks.afterCommit(() -> notifySeller(order, pointName));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orders", List.of(orderBrief(order)));
        return result;
    }

    /**
     * 建面交单 = 一张"约面交意向"，<b>不锁库存</b>。
     *
     * <p>锁库改到卖家接受时（{@link #accept}）：以前买家一提交就锁 30 分钟，而预约时间
     * 最长能到 7 天后，锁到期被定时任务自动释放，商品回到在售还能被别人再下一单 —— 假锁。
     * 现在买家提交不动商品，卖家点"接受"才真正锁定（锁到时间窗结束 +2h），锁库时长与
     * 预约时长这才对得上。
     */
    private MeetupOrder doCreate(Long buyerId, Long itemId, Integer quantity, Long pointId,
                                 String start, String end, String remark, String batchNo) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (item.getStatus() != null && item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD);
        }
        if (item.getStatus() != null && item.getStatus() == 3) {
            throw new BizException(ResultCode.ITEM_LOCKED, "商品已被其他同学约走，看看同款？");
        }
        if (item.getStatus() == null || item.getStatus() != 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品当前不可约面交");
        }
        // 自己约自己的商品：前端会拦，接口也拦一道
        if (Objects.equals(item.getSellerId(), buyerId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能约自己发布的商品");
        }
        // 同一件商品、同一个买家，**同时只能有一张进行中的面交单**（1 待确认 / 2 待面交）。
        // 以前没这道校验，买家可以反复点「约面交」刷出一堆重复单：
        // 卖家那边看到 N 条一模一样的申请，接受哪条都别扭。
        long active = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getBuyerId, buyerId)
                .eq(MeetupOrder::getItemId, itemId)
                .in(MeetupOrder::getStatus, STATUS_PENDING, STATUS_WAITING));
        if (active > 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR,
                    "你已经约过这件商品了，等卖家确认就行（想改细节去私聊）");
        }
        TradePoint point = pointId == null ? null : tradePointService.getById(pointId);
        if (pointId != null && point == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "面交点不存在");
        }

        MeetupOrder order = new MeetupOrder();
        order.setOrderNo(genOrderNo());
        order.setBatchNo(batchNo);
        order.setBuyerId(buyerId);
        order.setSellerId(item.getSellerId());
        order.setItemId(itemId);
        order.setItemTitle(item.getTitle());
        order.setItemCover(item.getCoverUrl());
        order.setQuantity(quantity == null || quantity < 1 ? 1 : quantity);
        order.setDealPrice(item.getPrice());
        order.setPointId(pointId);
        order.setPointSnapshot(point == null ? null : snapshot(point));
        order.setTimeSlotStart(StringUtils.hasText(start) ? LocalDateTime.parse(start, FMT) : null);
        order.setTimeSlotEnd(StringUtils.hasText(end) ? LocalDateTime.parse(end, FMT) : null);
        order.setStatus(STATUS_PENDING);
        order.setRescheduleCount(0);
        order.setRemark(remark);
        orderMapper.insert(order);
        log.info("[order][create][{}] orderNo={} item={} 约面交意向已提交（未锁库）",
                buyerId, order.getOrderNo(), itemId);
        return order;
    }

    // ---------------------------------------------------------------- 平台代卖下单（第 2 轮）

    /**
     * 平台代卖下单（平台作为卖家）：平台商品直购 / 秒杀抢购成功后建一张「待自提」面交单。
     *
     * <p><b>签名由第 2 轮设计 3.5 固定</b>（platform-dev 的直购/抢购 controller 直接调用），
     * 参数顺序与类型不要改。
     *
     * <p>与个人商品的 {@link #createSingle} 的区别：
     * <ul>
     *   <li>卖家是平台官方账号（{@link #platformSellerId}），不是真实用户，谁也点不了"接受"；</li>
     *   <li><b>不是"意向单"而是直接扣库存（不锁库）</b>：平台没有"卖家确认"这一步，抢到就要留货；</li>
     *   <li>订单直接进 status=2（待自提），并写 {@code seller_confirm_at=NOW()} 表示平台已自动确认；</li>
     *   <li>面交点是平台代卖点（{@code platform_consign_point.id}，<b>不是</b> trade_point 的 id）；</li>
     *   <li>不发"卖家待办"私聊：平台账号不可登录，也没有人需要处理待办。</li>
     * </ul>
     *
     * <p><b>防超卖（v1.1）</b>：{@code PlatformTradeMapper#decrementPlatformStock} 是一条条件更新
     * （{@code WHERE seller_type=2 AND status=2 AND stock>=?}），影响 0 行就抛 30004 ——
     * 并发抢购时由数据库行锁保证库存不会扣成负数。**平台商品不进入 status=3 锁定态**：
     * 一旦锁库，多库存秒杀的 5 份库存会退化成"一次只能卖一份"，还会被 status=3 的锁过期任务误伤。
     *
     * @param itemId    平台商品 id
     * @param buyerId   买家 user id
     * @param quantity  购买数量（null / &lt;1 当 1；平台商品可能有多库存）
     * @param dealPrice 成交价（秒杀价或商品价；null 时回退商品现价）
     * @param pointId   平台代卖/自提点 id（可空 = 待协商）
     * @return 至少含 orderId/orderNo/itemId/itemTitle/itemCover/dealPrice/pointId（设计 3.5）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> platformBuy(Long itemId, Long buyerId, Integer quantity,
                                           BigDecimal dealPrice, Long pointId) {
        if (itemId == null || buyerId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少商品或买家");
        }
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        // 1) 前置校验：给用户看得懂的错误；真正的防超卖闸门是下面的条件扣库存
        Integer sellerType = platformTradeMapper.selectSellerType(itemId);
        if (sellerType == null || sellerType != SELLER_TYPE_PLATFORM) {
            throw new BizException(ResultCode.PARAM_ERROR, "该商品不是平台代卖商品，不能走平台下单");
        }
        if (item.getStatus() != null && item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD);
        }
        if (item.getStatus() == null || item.getStatus() != 2) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品当前不可购买");
        }
        if (Objects.equals(item.getSellerId(), buyerId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能购买自己发布的商品");
        }
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        int stock = item.getStock() == null ? 0 : item.getStock();
        if (stock < qty) {
            throw new BizException(ResultCode.PARAM_ERROR, "库存不足（剩余 " + stock + " 件）");
        }
        BigDecimal price = dealPrice == null ? item.getPrice() : dealPrice;

        // 2) 条件扣库存（不锁库，v1.1）：并发由 WHERE stock>=? 保证不超卖；扣完库存归零 → status=4
        int deducted = platformTradeMapper.decrementPlatformStock(itemId, qty);
        if (deducted == 0) {
            throw new BizException(ResultCode.ITEM_LOCKED, "这件平台商品已售完或库存不足，看看同款？");
        }

        // 3) 建「待自提」面交单：平台账号作为卖家，已自动确认
        MeetupOrder order = new MeetupOrder();
        order.setOrderNo(genOrderNo());
        order.setBuyerId(buyerId);
        order.setSellerId(platformSellerId);
        order.setItemId(itemId);
        order.setItemTitle(item.getTitle());
        order.setItemCover(item.getCoverUrl());
        order.setQuantity(qty);
        order.setDealPrice(price);
        order.setPointId(pointId);
        // point_id 存的是 platform_consign_point.id，不是 trade_point.id：
        // 这里**不写 point_snapshot**（拿 trade_point 同 id 的点位拼快照只会拼错），
        // 订单列表的点位名由 toVO 按平台点表取（PlatformTradeMapper#selectPlatformPointName）
        order.setStatus(STATUS_WAITING);
        order.setRescheduleCount(0);
        order.setSellerConfirmAt(LocalDateTime.now());
        order.setRemark(PLATFORM_ORDER_REMARK);
        orderMapper.insert(order);

        // 4) 事务后：同步 ES + 清商品缓存（商品 status/stock 都变了）
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("itemId", itemId);
        result.put("itemTitle", item.getTitle());
        result.put("itemCover", item.getCoverUrl());
        result.put("dealPrice", price);
        result.put("pointId", pointId);
        result.put("quantity", qty);
        result.put("status", STATUS_WAITING);
        log.info("[order][platform-buy][{}] orderNo={} item={} qty={} price={} 平台单已扣库存（不锁库）",
                buyerId, order.getOrderNo(), itemId, qty, price);
        return result;
    }

    /**
     * 面交单的状态变化统一用私聊通知对方。
     *
     * <p>为什么走私聊：站内没有通知中心（notification 表还没实现），卖家/买家不主动点开
     * 「我的面交」就什么都不知道 —— 单子挂着、被拒了、商品被接受了都没有提示。
     *
     * <p>纯提醒，失败只记日志：拉黑 / 内容审核拦截都不该让交易动作失败。
     * 会话一律以<b>买家</b>作为发起方建（买家永远不是商品的卖家，openSession 才不会撞 70001）。
     */
    void notifyPeer(MeetupOrder order, Long actorId, String text) {
        try {
            ChatSessionVO session = chatService.openSession(order.getBuyerId(), order.getItemId());
            chatService.send(actorId, session.getId(), ChatService.MSG_TEXT, text, null);
        } catch (Exception e) {
            log.warn("[order][notify][{}] 面交提醒发送失败：{}", order.getId(), e.getMessage());
        }
    }

    /** 提交意向：买家 → 卖家 */
    void notifySeller(MeetupOrder order, String pointName) {
        String text = "【约面交】我想约《" + order.getItemTitle() + "》\n"
                + "面交点：" + (pointName == null ? "待协商（私聊里定）" : pointName) + "\n"
                + "时间：" + slotText(order) + "\n"
                + (StringUtils.hasText(order.getRemark()) ? "留言：" + order.getRemark() + "\n" : "")
                + "麻烦在「我的面交 → 我卖出的」里确认一下～";
        notifyPeer(order, order.getBuyerId(), text);
    }

    /** 点位文案：没选点位就是"待协商" */
    private String pointText(MeetupOrder order) {
        String name = order.getPointId() == null ? null : pointName(order.getPointId());
        return name == null ? "待协商（私聊里定）" : name;
    }

    /** 时间窗文案：没填就是"待协商" */
    private String slotText(MeetupOrder order) {
        if (order.getTimeSlotStart() == null || order.getTimeSlotEnd() == null) {
            return "待协商（私聊里定）";
        }
        return order.getTimeSlotStart().format(FMT) + " ~ " + order.getTimeSlotEnd().format(FMT);
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 我的面交单。
     *
     * @param itemId  可选：只看某件商品的单子（私聊窗里要显示"这单现在什么状态"）
     * @param status  可选：只看某个状态（私聊窗只关心进行中的 1/2）
     */
    public PageResult<OrderVO> page(Long userId, String role, Long itemId, Integer status,
                                    long pageNum, long pageSize) {
        boolean asSeller = "seller".equalsIgnoreCase(role);
        Page<MeetupOrder> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<MeetupOrder>lambdaQuery()
                        .eq(asSeller ? MeetupOrder::getSellerId : MeetupOrder::getBuyerId, userId)
                        .eq(itemId != null, MeetupOrder::getItemId, itemId)
                        .eq(status != null, MeetupOrder::getStatus, status)
                        .orderByDesc(MeetupOrder::getId));
        return PageResult.of(page, o -> toVO(o, userId));
    }

    /**
     * 待办数量：顶栏"我的面交"红点用。
     *
     * <p>只算<b>需要我动作</b>的单子，避免把"约好但还没到点"的也算成红点：
     * 卖家 = 待我确认(1)；买家 = 卖家已接受(2)但我还没点"我收到了"。
     */
    public Map<String, Object> todoCount(Long userId) {
        long asSeller = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getSellerId, userId)
                .eq(MeetupOrder::getStatus, 1));
        long asBuyer = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getBuyerId, userId)
                .eq(MeetupOrder::getStatus, 2)
                .isNull(MeetupOrder::getBuyerConfirmAt));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asSeller", asSeller);
        result.put("asBuyer", asBuyer);
        result.put("total", asSeller + asBuyer);
        return result;
    }

    public OrderVO detail(Long orderId, Long userId) {
        return toVO(requireOrder(orderId, userId), userId);
    }

    // ---------------------------------------------------------------- 状态流转

    /**
     * 卖家接受约面交：<b>这时才锁库存</b>。
     *
     * <p>锁到"时间窗结束 + 2 小时"（没填时间窗就锁 24 小时），锁库失败说明商品已经被
     * 别人拿走或已售出 —— 把这张单子置为已失效并明确告知，而不是留一张永远履约不了的单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long orderId, Long userId) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION, "只有卖家可以确认面交");
        }
        if (order.getStatus() != STATUS_PENDING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = order.getTimeSlotEnd() == null
                ? now.plusHours(LOCK_DEFAULT_HOURS)
                : order.getTimeSlotEnd().plusHours(LOCK_BUFFER_HOURS);
        if (expireAt.isBefore(now)) {
            // 约的时间已经过去了（例如卖家隔天才看到），给 2 小时缓冲，别一接受就解锁
            expireAt = now.plusHours(LOCK_BUFFER_HOURS);
        }
        int locked = itemMapper.lockStock(order.getItemId(), order.getBuyerId(), expireAt);
        if (locked == 0) {
            // 置失效必须走独立事务：下面要抛 30004，写在同一个事务里会被一起回滚
            orderStateRecorder.invalidate(orderId, userId, "商品已被其他同学约走或已售出");
            throw new BizException(ResultCode.ITEM_LOCKED, "这件商品已被其他同学锁定或已售出，无法确认面交");
        }
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        TxHooks.afterCommit(() -> evictor.itemChanged(order.getItemId()));

        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(STATUS_WAITING);
        orderMapper.updateById(update);

        // 同一件商品的其他"待确认"意向一并作废：商品已经锁定给这一单，别人再等也没用，
        // 留着只会让卖家在列表里看到一堆点不动的单子（点接受必然失败）。
        // 先查出来是为了提交后能逐个通知到人 —— 只做批量 update 的话就不知道通知谁了。
        List<MeetupOrder> otherIntents = orderMapper.selectList(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getItemId, order.getItemId())
                .eq(MeetupOrder::getStatus, 1)
                .ne(MeetupOrder::getId, orderId));
        int others = 0;
        if (!otherIntents.isEmpty()) {
            others = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                    .eq(MeetupOrder::getItemId, order.getItemId())
                    .eq(MeetupOrder::getStatus, 1)
                    .ne(MeetupOrder::getId, orderId)
                    .set(MeetupOrder::getStatus, 5)
                    .set(MeetupOrder::getCancelReason, "卖家已把商品确认给其他同学"));
        }
        log.info("[order][accept][{}] orderNo={} 商品 {} 锁定至 {}，同时作废 {} 张其他意向单",
                userId, order.getOrderNo(), order.getItemId(), expireAt, others);
        TxHooks.afterCommit(() -> notifyPeer(order, userId,
                "【面交已确认】《" + order.getItemTitle() + "》我给你留着了。\n"
                        + "面交点：" + pointText(order) + "\n"
                        + "时间：" + slotText(order) + "\n"
                        + "到点见面验货就行，交易完成后我会点「已卖出」。"));
        for (MeetupOrder other : otherIntents) {
            TxHooks.afterCommit(() -> notifyPeer(other, order.getSellerId(),
                    "【约面交】《" + other.getItemTitle() + "》卖家已经确认给其他同学了，你再看看同款吧～"));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long orderId, Long userId, String reason) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (order.getStatus() != STATUS_PENDING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(STATUS_CANCELLED);
        update.setCancelBy(userId);
        update.setCancelReason(StringUtils.hasText(reason) ? reason : "卖家拒绝了面交");
        orderMapper.updateById(update);
        releaseItemLock(order);
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        TxHooks.afterCommit(() -> evictor.itemChanged(order.getItemId()));
        // 被拒绝必须让对方收到消息：以前只是在"我的面交"里默默变成已取消，买家根本不知道
        // 平台单不通知：对方是平台官方账号，没人会看（任务要求避免平台账号收到私聊）
        if (!isPlatformOrder(order)) {
            TxHooks.afterCommit(() -> notifyPeer(order, userId,
                    "【约面交】抱歉，《" + order.getItemTitle() + "》这次我没法赴约："
                            + update.getCancelReason() + "\n你可以换个时间再约，或者看看别的同学的同款～"));
        }
        log.info("[order][reject][{}] orderNo={} reason={}", userId, order.getOrderNo(), reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, Long userId, String reason) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() != STATUS_PENDING && order.getStatus() != STATUS_WAITING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(STATUS_REJECTED);
        update.setCancelBy(userId);
        update.setCancelReason(StringUtils.hasText(reason) ? reason : "双方协商取消");
        orderMapper.updateById(update);
        // 平台单购买时已扣库存，取消要还库存；个人单走 releaseLock 只回在售
        releaseItemLock(order);
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        TxHooks.afterCommit(() -> evictor.itemChanged(order.getItemId()));
        // 平台单不通知平台账号（没人看），个人单照旧私聊对方
        if (!isPlatformOrder(order)) {
            TxHooks.afterCommit(() -> notifyPeer(order, userId,
                    "【约面交】《" + order.getItemTitle() + "》这次面交取消了："
                            + update.getCancelReason() + "\n需要的话可以再约一次～"));
        }
        log.info("[order][cancel][{}] orderNo={}", userId, order.getOrderNo());
    }

    /**
     * 商品变得「不可交易」时，把还挂在上面的进行中面交单统一收掉（成交 / 卖家自己下架 / 运营强制下架）。
     *
     * <p>以前只有两条路会收别人的单：「卖家接受某一单」和「锁过期定时任务」。商品自己变得
     * 不可交易时一张都不收，其中最狠的是<b>已锁库的商品被下架</b>：锁只在 {@code status=3}
     * 时才会被释放（{@code ItemMapper#releaseLock}），商品一旦变成 5（已下架）或 8（违规下架）
     * 就永远等不到释放，那张「待面交」单会<b>永久卡住</b> —— 买家顶栏红点永远不消，
     * 而且没有任何定时任务会碰它（锁过期任务只捞 status=3 的商品，48 小时任务只管 status=1）。
     *
     * @param reason 买家可见的说明：写进 {@code cancel_reason}，并逐单私聊告知对方
     * @return 实际作废的张数
     */
    @Transactional(rollbackFor = Exception.class)
    public int closeActiveOrders(Long itemId, String reason) {
        if (itemId == null) {
            return 0;
        }
        // 先查出来是为了提交后能逐个通知到人 —— 只做批量 update 的话就不知道通知谁了
        List<MeetupOrder> active = orderMapper.selectList(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getItemId, itemId)
                .in(MeetupOrder::getStatus, STATUS_ACTIVE));
        if (active.isEmpty()) {
            return 0;
        }
        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getItemId, itemId)
                .in(MeetupOrder::getStatus, STATUS_ACTIVE)
                .set(MeetupOrder::getStatus, STATUS_CANCELLED)
                .set(MeetupOrder::getCancelReason, reason));
        TxHooks.afterCommit(() -> active.forEach(o ->
                notifyPeer(o, o.getSellerId(), "【约面交】《" + o.getItemTitle() + "》" + reason)));
        log.info("[order][close-active][item={}] 作废 {} 张进行中的面交单：{}", itemId, rows, reason);
        return rows;
    }

    /**
     * 确认成交。
     *
     * <p>卖家点一次即完成（线下钱货两清由卖家判断）：商品置已售出 + 双方信用分 +2。
     * 买家这一侧只记录"我收到了"，不推进状态 —— 否则买家单方面点一下就能把卖家的商品下架。
     * <b>例外是平台代卖单</b>：卖家是平台官方账号、没人能点"已卖出"，买家的「我已收到」直接完成，
     * 见 {@link #completePlatformOrder}。
     * 原来的 6 位核销码已经去掉：它带有效期（时间窗结束 +2h），迟到或改期就"核销码已过期"，
     * 这张单永远完不成，只能取消重约。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(Long orderId, Long userId) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() != STATUS_WAITING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不能确认成交");
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();
        if (Objects.equals(order.getBuyerId(), userId)) {
            // 平台代卖单：卖家是平台官方账号，下单时已自动写 seller_confirm_at，
            // 永远没有"卖家点已卖出"这一步 —— 所以买家的「我已收到」就是完成动作。
            // 否则平台单会永远停在「待面交」，买家红点也永远不消（第 2 轮 QA 验收项）。
            if (isPlatformOrder(order)) {
                return completePlatformOrder(order, now);
            }
            if (order.getBuyerConfirmAt() == null) {
                MeetupOrder update = new MeetupOrder();
                update.setId(orderId);
                update.setBuyerConfirmAt(now);
                orderMapper.updateById(update);
            }
            result.put("status", order.getStatus());
            result.put("message", "已记下「我收到了」，等卖家点「已卖出」交易就完成了");
            return result;
        }
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(STATUS_DONE);
        update.setSellerConfirmAt(now);
        orderMapper.updateById(update);

        itemMapper.markSold(order.getItemId());
        // 商品已卖出：这件商品上还挂着的其他意向 / 待面交单一起作废（本单此时已是 3，不会被误伤）
        int closed = closeActiveOrders(order.getItemId(), "商品已卖给其他同学，本次约面交自动作废");
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        TxHooks.afterCommit(() -> evictor.itemChanged(order.getItemId()));
        userMapper.addCredit(order.getBuyerId(), 2);
        userMapper.addCredit(order.getSellerId(), 2);
        TxHooks.afterCommit(() -> notifyPeer(order, userId,
                "【交易完成】《" + order.getItemTitle() + "》已标记卖出，双方信用分 +2，谢谢配合！"));
        log.info("[order][finished][{}] orderNo={} 交易完成，双方信用分 +2，同时作废 {} 张挂单",
                userId, order.getOrderNo(), closed);
        result.put("status", 3);
        result.put("message", "交易已完成，双方信用分 +2");
        return result;
    }

    // ---------------------------------------------------------------- 工具

    private MeetupOrder requireOrder(Long orderId, Long userId) {
        MeetupOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (!Objects.equals(order.getBuyerId(), userId) && !Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION);
        }
        return order;
    }

    /** 是不是平台代卖单：平台单的卖家是平台官方账号（第 2 轮设计 2.3） */
    private boolean isPlatformOrder(MeetupOrder order) {
        return order != null && platformSellerId != null && platformSellerId.equals(order.getSellerId());
    }

    /**
     * 取消/拒绝时释放商品占用：平台单要<b>还库存</b>，个人单只回在售。
     *
     * <p>个人商品的 {@code ItemMapper.releaseLock} 只改状态、不还库存（个人单本来就没扣过库存）；
     * 平台单在 {@code platformBuy} 里已经 {@code stock = stock - ?} 扣过，取消必须补回去，
     * 否则商品回到在售后会"凭空少一件"。库存归零时平台商品已被置 4，还库存后由 SQL 一并回在售 2。
     */
    private void releaseItemLock(MeetupOrder order) {
        if (isPlatformOrder(order)) {
            int qty = order.getQuantity() == null || order.getQuantity() < 1 ? 1 : order.getQuantity();
            platformTradeMapper.restorePlatformStock(order.getItemId(), qty);
        } else {
            itemMapper.releaseLock(order.getItemId());
        }
    }

    /**
     * 平台代卖单完成：买家「我已收到」直接落定（平台账号不会点「已卖出」）。
     *
     * <p>与个人单卖家成交的区别：
     * <ul>
     *   <li>商品按<b>剩余库存</b>收口（{@code PlatformTradeMapper#settlePlatformItem}）：
     *       还剩库存 → 回在售(2) 让别人继续买，已扣到 0 → 已售出(4)。
     *       不能复用 {@code ItemMapper.markSold}：它会无条件 {@code stock=0}，
     *       多库存平台商品（秒杀 5 份）的剩余份数会被清掉；</li>
     *   <li><b>不作废该商品上的其他单</b>：平台商品多库存、允许并发成交，别人的单不能跟着失效；</li>
     *   <li>平台账号不加信用分、不写流水（不可登录、也不该有信用分），买家仍 +2；</li>
     *   <li>不给平台账号发私聊：平台账号收不到也没有人看，避免污染会话（任务要求）。</li>
     * </ul>
     */
    private Map<String, Object> completePlatformOrder(MeetupOrder order, LocalDateTime now) {
        MeetupOrder update = new MeetupOrder();
        update.setId(order.getId());
        update.setStatus(STATUS_DONE);
        update.setBuyerConfirmAt(now);
        if (order.getSellerConfirmAt() == null) {
            update.setSellerConfirmAt(now);
        }
        orderMapper.updateById(update);

        // 按剩余库存收口商品状态（设计 3.5 第 6 条：影响 0 行 → 抛错回滚）。
        // 0 行只会出现在商品已被下架/违规下架/过期（status 5/7/8）的情况 ——
        // 那种单子早该由 closeActiveOrders 作废，宁可报错让买家刷新，也不留"单子已完成但商品状态矛盾"
        int settled = platformTradeMapper.settlePlatformItem(order.getItemId());
        if (settled == 0) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品状态已变化，无法完成交易，请刷新后重试");
        }
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        TxHooks.afterCommit(() -> evictor.itemChanged(order.getItemId()));
        // 信用分：买家 +2；平台卖家（seller_id=平台账号）不加分、不写 credit_log
        userMapper.addCredit(order.getBuyerId(), 2);
        log.info("[order][platform-finished][{}] orderNo={} 平台单已完成（商品按剩余库存收口），买家信用分 +2（平台账号不加分）",
                order.getBuyerId(), order.getOrderNo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", STATUS_DONE);
        result.put("message", "交易已完成，信用分 +2");
        return result;
    }

    /** 时间窗校验：两边都留空 = 待协商（合法）；只填一边、或填了但越界才算错 */
    private void validateSlot(String start, String end) {
        boolean blankStart = !StringUtils.hasText(start);
        boolean blankEnd = !StringUtils.hasText(end);
        if (blankStart && blankEnd) {
            return;
        }
        if (blankStart || blankEnd) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "时间窗要么都填、要么都留空（留空表示和卖家商量）");
        }
        LocalDateTime s;
        LocalDateTime e;
        try {
            s = LocalDateTime.parse(start, FMT);
            e = LocalDateTime.parse(end, FMT);
        } catch (Exception ex) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "时间格式应为 yyyy-MM-dd HH:mm:ss");
        }
        if (!e.isAfter(s)) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "结束时间需晚于开始时间");
        }
        if (Duration.between(s, e).toMinutes() > MAX_SLOT_MINUTES) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "单次面交时间窗不能超过 3 小时");
        }
        if (s.isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "开始时间不能早于当前时间");
        }
        if (s.isAfter(LocalDateTime.now().plusDays(7))) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "最多只能预约 7 天内的面交");
        }
    }

    private String snapshot(TradePoint p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("guideText", p.getGuideText());
        map.put("safetyScore", p.getSafetyScore());
        map.put("lng", p.getLng());
        map.put("lat", p.getLat());
        map.put("coordX", p.getCoordX());
        map.put("coordY", p.getCoordY());
        try {
            return json.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- 改面交地点

    /**
     * 改面交地点（可选同时改时间窗）——买卖双方都能改，改完私聊通知对方。
     *
     * <p>为什么允许双方改：校园里"3 号楼下"和"图书馆东门"哪个方便是协商出来的，
     * 只让买家改的话卖家想说"今天食堂那边近"就没辙，只能打字，信息还散落在聊天记录里。
     *
     * <p>状态约束：只有待卖家确认(1) / 待面交(2) 能改。已完成(3)/已取消(4)/已失效(5)
     * 再改没有意义，改出来的结果也不会有人看。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO updatePoint(Long orderId, Long userId, UpdatePointDTO dto) {
        MeetupOrder order = requireOrder(orderId, userId);
        // **只有卖家能改面交地点与时间**（产品决定）：商品在卖家手上，见面的地点与时间由他定更合理；
        // 买家想改就在私聊里商量。校验必须在服务端——只藏按钮挡不住直接调接口。
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION, "只有卖家能修改面交地点和时间");
        }
        if (order.getStatus() == null || (order.getStatus() != STATUS_PENDING && order.getStatus() != STATUS_WAITING)) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不能改面交地点");
        }
        if (dto == null || dto.getPointId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择新的面交点");
        }
        TradePoint point = tradePointService.getById(dto.getPointId());
        if (point == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "面交点不存在");
        }
        // 防止把别的小区的点挂上来（地图上根本找不到）
        Item item = itemMapper.selectById(order.getItemId());
        if (item != null && item.getCampusId() != null && point.getCampusId() != null
                && !Objects.equals(item.getCampusId(), point.getCampusId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "只能选择本校区的面交点");
        }
        boolean changeSlot = StringUtils.hasText(dto.getTimeSlotStart()) || StringUtils.hasText(dto.getTimeSlotEnd());
        if (changeSlot) {
            validateSlot(dto.getTimeSlotStart(), dto.getTimeSlotEnd());
        }

        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setPointId(point.getId());
        update.setPointSnapshot(snapshot(point));
        if (changeSlot) {
            update.setTimeSlotStart(LocalDateTime.parse(dto.getTimeSlotStart(), FMT));
            update.setTimeSlotEnd(LocalDateTime.parse(dto.getTimeSlotEnd(), FMT));
        }
        update.setRescheduleCount((order.getRescheduleCount() == null ? 0 : order.getRescheduleCount()) + 1);
        orderMapper.updateById(update);

        // 走到这里一定是卖家（上面已校验），文案就不要再按角色猜了
        TxHooks.afterCommit(() -> notifyPeer(order, userId,
                "【面交地点已更新】《" + order.getItemTitle() + "》\n"
                        + "卖家把面交点改成了：" + point.getName() + "\n"
                        + (StringUtils.hasText(point.getGuideText()) ? "指引：" + point.getGuideText() + "\n" : "")
                        + "时间：" + (changeSlot
                        ? FMT.format(LocalDateTime.parse(dto.getTimeSlotStart(), FMT))
                        + " ~ " + FMT.format(LocalDateTime.parse(dto.getTimeSlotEnd(), FMT))
                        : slotText(order)) + "\n"
                        + "没意见的话到点见面就行～"));
        log.info("[order][updatePoint][{}] orderNo={} 面交点改为 {}（第 {} 次调整）",
                userId, order.getOrderNo(), point.getName(), update.getRescheduleCount());

        MeetupOrder latest = orderMapper.selectById(orderId);
        return toVO(latest, userId);
    }

    /** 下单回执：不再回 lockExpireAt —— 提交意向不锁库，商品是卖家接受时才锁的 */
    private Map<String, Object> orderBrief(MeetupOrder o) {
        TradePoint p = o.getPointId() == null ? null : tradePointService.getById(o.getPointId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("sellerId", o.getSellerId());
        m.put("pointName", p == null ? null : p.getName());
        return m;
    }

    private String genOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String genBatchNo() {
        return "B" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    public OrderVO toVO(MeetupOrder o, Long currentUserId) {
        OrderVO vo = new OrderVO();
        vo.setId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setBatchNo(o.getBatchNo());
        vo.setItemId(o.getItemId());
        vo.setItemTitle(o.getItemTitle());
        vo.setItemCover(o.getItemCover());
        vo.setQuantity(o.getQuantity());
        vo.setDealPrice(o.getDealPrice());
        vo.setPointId(o.getPointId());
        vo.setTimeSlotStart(o.getTimeSlotStart());
        vo.setTimeSlotEnd(o.getTimeSlotEnd());
        vo.setStatus(o.getStatus());
        vo.setStatusText(statusText(o.getStatus()));
        vo.setRemark(o.getRemark());
        vo.setCancelReason(o.getCancelReason());
        vo.setCreatedAt(o.getCreatedAt());
        vo.setVerifiedAt(o.getVerifiedAt());
        vo.setBuyerId(o.getBuyerId());
        vo.setSellerId(o.getSellerId());
        vo.setBuyerConfirmed(o.getBuyerConfirmAt() != null);
        vo.setSellerConfirmed(o.getSellerConfirmAt() != null);
        vo.setMyRole(Objects.equals(o.getBuyerId(), currentUserId) ? "buyer" : "seller");
        if (isPlatformOrder(o)) {
            // 平台单的面交点是 platform_consign_point.id，去 trade_point 里查会命中同 id 的无关点位。
            // 这里按平台点表取名称；点名称/指引由平台模块维护（order 侧只读，不改平台表）
            if (o.getPointId() != null) {
                vo.setPointName(platformTradeMapper.selectPlatformPointName(o.getPointId()));
            }
        } else {
            TradePoint p = tradePointService.getById(o.getPointId());
            if (p != null) {
                vo.setPointName(p.getName());
                vo.setPointGuide(p.getGuideText());
            }
        }
        Map<Long, SysUser> users = userService.mapByIds(List.of(o.getBuyerId(), o.getSellerId()));
        SysUser buyer = users.get(o.getBuyerId());
        SysUser seller = users.get(o.getSellerId());
        vo.setBuyerName(buyer == null ? null : buyer.getNickname());
        vo.setSellerName(seller == null ? null
                : (seller.getShopName() != null ? seller.getShopName() : seller.getNickname()));
        return vo;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "待卖家确认";
            case 2 -> "待面交";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已失效";
            case 6 -> "爽约";
            case 7 -> "纠纷中";
            default -> "未知";
        };
    }
}