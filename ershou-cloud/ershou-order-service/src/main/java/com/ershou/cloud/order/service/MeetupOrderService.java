package com.ershou.cloud.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.order.config.OrderProperties;
import com.ershou.cloud.order.dto.CheckoutDTO;
import com.ershou.cloud.order.dto.CreateOrderDTO;
import com.ershou.cloud.order.dto.InnerCreateOrderDTO;
import com.ershou.cloud.order.dto.OrderVO;
import com.ershou.cloud.order.dto.UpdatePointDTO;
import com.ershou.cloud.order.entity.CartItem;
import com.ershou.cloud.order.entity.MeetupOrder;
import com.ershou.cloud.order.event.OrderCreatedEvent;
import com.ershou.cloud.order.feign.InnerItemVO;
import com.ershou.cloud.order.feign.InnerPointVO;
import com.ershou.cloud.order.feign.InnerUserVO;
import com.ershou.cloud.order.feign.ItemClient;
import com.ershou.cloud.order.feign.PointClient;
import com.ershou.cloud.order.feign.UserClient;
import com.ershou.cloud.order.mapper.MeetupOrderMapper;
import com.ershou.cloud.order.util.Texts;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 面交单服务（第 103 轮全量拆分：从单体 {@code module.order.service.OrderService} 迁入）。
 *
 * <p>与单体版本的差异（都是"拆库/拆服务"带来的）：
 * <ul>
 *   <li>商品状态读写走 item-service Feign：{@code GET /inner/items/{id}}、面交锁库/解锁/成交内部接口；</li>
 *   <li>买家/卖家昵称走 user-service Feign（降级为 null）；点位名称/快照走 map-service Feign（降级为 null）；</li>
 *   <li>信用分：{@code credit_log} 写本库，{@code sys_user.credit_score} 走 user-service Feign（降级仅记流水）；</li>
 *   <li>站内私聊提醒（单体 ChatService）在拆分契约里没有 chat-service 内部发送接口 → 本轮暂不发送，报告标为遗留。</li>
 * </ul>
 *
 * <p>状态机与旧单体保持一致：1 待卖家确认 →（卖家接受，锁商品）→ 2 待面交 →（卖家点已卖出）→ 3 已完成；
 * 4 已取消 / 5 已失效；同一买家 + 同一商品同时只能有一张进行中单（40003）。
 * 并发安全做了加固：状态流转用**条件更新**（{@code WHERE status=旧值}）+ 校验影响行数，避免双击重复完成/重复加信用分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetupOrderService {

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_CANCELLED = 4;
    public static final int STATUS_INVALID = 5;
    public static final List<Integer> STATUS_ACTIVE = List.of(STATUS_PENDING, STATUS_WAITING);

    /** 内部接口调用降级码（与 common ResultCode.SYSTEM_ERROR 同码，单品服务 fallback 返回它） */
    private static final int CODE_SERVICE_UNAVAILABLE = 90001;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MeetupOrderMapper orderMapper;
    private final CartService cartService;
    private final ItemClient itemClient;
    private final UserClient userClient;
    private final PointClient pointClient;
    private final CreditService creditService;
    private final OrderStateRecorder orderStateRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final OrderProperties props;

    // ================================================================ 创建

    /** 购物车合并结算（与旧单体 JSON 完全一致：{batchNo, orders:[...], skipped:[...]}） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(Long buyerId, CheckoutDTO dto) {
        List<CartItem> cartItems = cartService.rawByIds(buyerId, dto.getCartIds());
        if (cartItems.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "购物车里没有可结算的商品");
        }
        List<CartItem> ok = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        Map<Long, InnerItemVO> itemMap = new HashMap<>();
        for (CartItem ci : cartItems) {
            InnerItemVO item = loadItemForCheckout(ci.getItemId());
            itemMap.put(ci.getItemId(), item);
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
            InnerItemVO item = itemMap.get(ci.getItemId());
            Long pointId = buyerSidePoint(item);
            MeetupOrder order = doCreate(buyerId, ci.getItemId(), ci.getQuantity(), pointId,
                    dto.getRemark(), batchNo, item);
            created.add(orderBrief(order));
            usedCartIds.add(ci.getId());
        }
        cartService.removeByIds(usedCartIds);
        // 事务提交后发 MQ（item-service 消费累加 sale_count；失败只记日志不影响下单）
        publishCreatedEvents(ok, batchNo, buyerId);
        log.info("[order][checkout][{}] batch={} 生成 {} 张面交单（跳过 {} 件不可面交）",
                buyerId, batchNo, created.size(), skipped.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orders", created);
        result.put("skipped", skipped);
        return result;
    }

    /** 单品直接约面交（与旧单体 JSON 一致：{batchNo, orders:[brief]}） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSingle(Long buyerId, CreateOrderDTO dto) {
        InnerItemVO item = loadItem(dto.getItemId());
        Long pointId = buyerSidePoint(item);
        String batchNo = genBatchNo();
        MeetupOrder order = doCreate(buyerId, dto.getItemId(), dto.getQuantity(), pointId,
                dto.getRemark(), batchNo, item);
        if (Boolean.TRUE.equals(dto.getDebugFail())) {
            log.warn("[order][create][debugFail] 演示用失败：抛异常触发回滚 orderNo={}", order.getOrderNo());
            throw new BizException(ResultCode.SYSTEM_ERROR, "演示用失败：debugFail=true 触发全局回滚");
        }
        publishCreated(order);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orders", List.of(orderBrief(order)));
        return result;
    }

    /**
     * 建面交单 = 一张"约面交意向"，<b>不锁库存</b>（锁库在卖家 accept 时）。
     * 与单体同一套校验：状态/自己商品/同买家同商品进行中单防重（40003）。
     */
    private MeetupOrder doCreate(Long buyerId, Long itemId, Integer quantity, Long pointId,
                                 String remark, String batchNo, InnerItemVO item) {
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
        if (Objects.equals(item.getSellerId(), buyerId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能约自己发布的商品");
        }
        long active = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getBuyerId, buyerId)
                .eq(MeetupOrder::getItemId, itemId)
                .in(MeetupOrder::getStatus, STATUS_ACTIVE));
        if (active > 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR,
                    "你已经约过这件商品了，等卖家确认就行（想改细节去私聊）");
        }
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        if (qty > props.getMaxQty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "单次最多购买 " + props.getMaxQty() + " 件");
        }
        InnerPointVO point = pointId == null ? null : loadPointOrNull(pointId);
        if (pointId != null && point == null) {
            // 兼容旧单体提示，但保留 pointId（地图服务未就绪时降级：无快照，前端显示待协商）
            log.warn("[order][create] 点位 {} 详情不可用（map-service 未就绪或已删除），写入 pointId 但无快照", pointId);
        }

        MeetupOrder order = new MeetupOrder();
        order.setOrderNo(genOrderNo());
        order.setBatchNo(batchNo);
        order.setBuyerId(buyerId);
        order.setSellerId(item.getSellerId());
        order.setItemId(itemId);
        order.setItemTitle(item.getTitle());
        order.setItemCover(item.getCoverUrl());
        order.setQuantity(qty);
        order.setDealPrice(item.getPrice() == null ? BigDecimal.ZERO : item.getPrice());
        order.setPointId(pointId);
        order.setPointSnapshot(point == null ? null : snapshot(point));
        order.setStatus(props.getDefaultStatus());
        order.setRescheduleCount(0);
        order.setRemark(remark);
        orderMapper.insert(order);
        log.info("[order][create][{}] orderNo={} item={} qty={} 面交意向已提交（未锁库）",
                buyerId, order.getOrderNo(), itemId, qty);
        return order;
    }

    /** 商品为什么不能约面交；能约返回 null（与 doCreate 同一套口径；null 商品=已删除） */
    private String unavailableReason(InnerItemVO item, Long buyerId) {
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

    /** 买家提交意向时的面交点：一律用商品自己关联的点位（买家传的 pointId 被忽略，与单体一致） */
    private Long buyerSidePoint(InnerItemVO item) {
        return item == null ? null : item.getPreferPointId();
    }

    // ================================================================ 查询

    /** 我的面交单（role=buyer|seller；可按 itemId/status 过滤） */
    public PageResult<OrderVO> page(Long userId, String role, Long itemId, Integer status,
                                    long pageNum, long pageSize) {
        boolean asSeller = "seller".equalsIgnoreCase(role);
        Page<MeetupOrder> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<MeetupOrder>lambdaQuery()
                        .eq(asSeller ? MeetupOrder::getSellerId : MeetupOrder::getBuyerId, userId)
                        .eq(itemId != null, MeetupOrder::getItemId, itemId)
                        .eq(status != null, MeetupOrder::getStatus, status)
                        .orderByDesc(MeetupOrder::getId));
        return PageResult.of(toVOList(page.getRecords(), userId), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 待办红点：卖家待确认(1) + 买家待收货(2 且未点我收到了) */
    public Map<String, Object> todoCount(Long userId) {
        long asSeller = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getSellerId, userId).eq(MeetupOrder::getStatus, STATUS_PENDING));
        long asBuyer = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getBuyerId, userId).eq(MeetupOrder::getStatus, STATUS_WAITING)
                .isNull(MeetupOrder::getBuyerConfirmAt));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asSeller", asSeller);
        result.put("asBuyer", asBuyer);
        result.put("total", asSeller + asBuyer);
        return result;
    }

    /** 面交单详情（前端用：只允许买卖双方查看 → IDOR 防护） */
    public OrderVO detail(Long orderId, Long userId) {
        return toVOList(List.of(requireOrder(orderId, userId)), userId).get(0);
    }

    // ================================================================ 状态流转

    /**
     * 卖家接受：这时才锁商品（item-service 面交锁库接口，参与 Seata AT）。
     * 锁库明确失败（商品已被别人锁走/已售出）→ 本单置失效（独立事务）并抛 30004；
     * item-service 迁移期未实现接口（fallback 90001）→ 降级：订单照常置待面交但商品未锁库，log.warn。
     */
    @GlobalTransactional(name = "meetup-accept")
    @Transactional(rollbackFor = Exception.class)
    public void accept(Long orderId, Long userId) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION, "只有卖家可以确认面交");
        }
        if (order.getStatus() == null || order.getStatus() != STATUS_PENDING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = order.getTimeSlotEnd() == null
                ? now.plusHours(props.getLockDefaultHours())
                : order.getTimeSlotEnd().plusHours(props.getLockBufferHours());
        if (expireAt.isBefore(now)) {
            expireAt = now.plusHours(props.getLockBufferHours());
        }
        lockItemForMeetup(order, expireAt);

        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getId, orderId)
                .eq(MeetupOrder::getStatus, STATUS_PENDING)
                .set(MeetupOrder::getStatus, STATUS_WAITING));
        if (rows == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        // 同一商品其他"待确认"意向一并作废（商品已锁定给这一单）
        int others = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getItemId, order.getItemId())
                .eq(MeetupOrder::getStatus, STATUS_PENDING)
                .ne(MeetupOrder::getId, orderId)
                .set(MeetupOrder::getStatus, STATUS_INVALID)
                .set(MeetupOrder::getCancelReason, "卖家已把商品确认给其他同学"));
        log.info("[order][accept][{}] orderNo={} 商品 {} 锁定至 {}，同时作废 {} 张其他意向单",
                userId, order.getOrderNo(), order.getItemId(), expireAt, others);
    }

    /** 卖家拒绝（仅 status=1）：置已失效 + 释放商品锁（best-effort） */
    @GlobalTransactional(name = "meetup-reject")
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long orderId, Long userId, String reason) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (order.getStatus() == null || order.getStatus() != STATUS_PENDING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getId, orderId)
                .eq(MeetupOrder::getStatus, STATUS_PENDING)
                .set(MeetupOrder::getStatus, STATUS_INVALID)
                .set(MeetupOrder::getCancelBy, userId)
                .set(MeetupOrder::getCancelReason, StringUtils.hasText(reason) ? reason : "卖家拒绝了面交"));
        if (rows == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        releaseItemBestEffort(order);
        log.info("[order][reject][{}] orderNo={} reason={}", userId, order.getOrderNo(), reason);
    }

    /** 取消（买卖双方在 1/2 状态可取消）：置已取消 + 释放商品锁（best-effort） */
    @GlobalTransactional(name = "meetup-cancel")
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, Long userId, String reason) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() == null
                || (order.getStatus() != STATUS_PENDING && order.getStatus() != STATUS_WAITING)) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getId, orderId)
                .in(MeetupOrder::getStatus, STATUS_ACTIVE)
                .set(MeetupOrder::getStatus, STATUS_CANCELLED)
                .set(MeetupOrder::getCancelBy, userId)
                .set(MeetupOrder::getCancelReason, StringUtils.hasText(reason) ? reason : "双方协商取消"));
        if (rows == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        releaseItemBestEffort(order);
        log.info("[order][cancel][{}] orderNo={}", userId, order.getOrderNo());
    }

    /**
     * 确认成交（与旧单体语义一致）：
     * <ul>
     *   <li>买家：平台代卖单直接完成（平台账号不会点已卖出）；普通单只写 buyer_confirm_at 留痕；</li>
     *   <li>卖家：条件更新 2→3（防并发双击重复完成）+ 商品置已售出 + 双方信用分 +2 + 作废同商品其它挂单。</li>
     * </ul>
     */
    @GlobalTransactional(name = "meetup-confirm")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(Long orderId, Long userId) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() == null || order.getStatus() != STATUS_WAITING) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不能确认成交");
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();
        if (Objects.equals(order.getBuyerId(), userId)) {
            if (isPlatformOrder(order)) {
                return completePlatformOrder(order, now);
            }
            if (order.getBuyerConfirmAt() == null) {
                orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                        .eq(MeetupOrder::getId, orderId).set(MeetupOrder::getBuyerConfirmAt, now));
            }
            result.put("status", order.getStatus());
            result.put("message", "已记下「我收到了」，等卖家点「已卖出」交易就完成了");
            return result;
        }
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION);
        }
        // 条件更新：只有仍在"待面交"才允许完成（双击/重放只有一个成功，信用分不会重复加）
        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getId, orderId)
                .eq(MeetupOrder::getStatus, STATUS_WAITING)
                .set(MeetupOrder::getStatus, STATUS_DONE)
                .set(MeetupOrder::getSellerConfirmAt, now));
        if (rows == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不能确认成交");
        }
        markItemSoldBestEffort(order);
        int closed = closeActiveOrders(order.getItemId(), "商品已卖给其他同学，本次约面交自动作废");
        // 信用分：双方 +2（平台账号不加分）；credit_log 落本库，score 由 user-service 改（降级仅记流水）
        if (!isPlatformOrder(order)) {
            creditService.addCredit(order.getBuyerId(), 2, "面交完成 +2（订单 " + order.getOrderNo() + "）",
                    "order", orderId, userId);
            creditService.addCredit(order.getSellerId(), 2, "面交完成 +2（订单 " + order.getOrderNo() + "）",
                    "order", orderId, userId);
        }
        log.info("[order][finished][{}] orderNo={} 交易完成，双方信用分 +2，同时作废 {} 张挂单",
                userId, order.getOrderNo(), closed);
        result.put("status", STATUS_DONE);
        result.put("message", "交易已完成，双方信用分 +2");
        return result;
    }

    /** 平台代卖单完成：买家「我已收到」即完成；商品按剩余库存收口（item-service 接口，降级仅记 warn） */
    private Map<String, Object> completePlatformOrder(MeetupOrder order, LocalDateTime now) {
        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getId, order.getId())
                .eq(MeetupOrder::getStatus, STATUS_WAITING)
                .set(MeetupOrder::getStatus, STATUS_DONE)
                .set(MeetupOrder::getBuyerConfirmAt, now)
                .set(MeetupOrder::getSellerConfirmAt,
                        order.getSellerConfirmAt() == null ? now : order.getSellerConfirmAt()));
        if (rows == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不能确认成交");
        }
        platformSettleBestEffort(order);
        creditService.addCredit(order.getBuyerId(), 2, "平台自提完成 +2（订单 " + order.getOrderNo() + "）",
                "order", order.getId(), null);
        log.info("[order][platform-finished][{}] orderNo={} 平台单已完成，买家信用分 +2（平台账号不加分）",
                order.getBuyerId(), order.getOrderNo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", STATUS_DONE);
        result.put("message", "交易已完成，信用分 +2");
        return result;
    }

    /**
     * 改面交地点/时间（只允许卖家；状态 1/2；时间窗规则与单体一致）。
     * 点位详情走 map-service（不可用时降级：写入 pointId 但无快照）。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO updatePoint(Long orderId, Long userId, UpdatePointDTO dto) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION, "只有卖家能修改面交地点和时间");
        }
        if (order.getStatus() == null
                || (order.getStatus() != STATUS_PENDING && order.getStatus() != STATUS_WAITING)) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态不能改面交地点");
        }
        if (dto == null || dto.getPointId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择新的面交点");
        }
        InnerPointVO point = loadPointOrNull(dto.getPointId());
        InnerItemVO item = loadItemOrNull(order.getItemId());
        if (item != null && item.getCampusId() != null && point != null
                && point.getCampusId() != null && !Objects.equals(item.getCampusId(), point.getCampusId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "只能选择本校区的面交点");
        }
        boolean changeSlot = StringUtils.hasText(dto.getTimeSlotStart())
                || StringUtils.hasText(dto.getTimeSlotEnd());
        if (changeSlot) {
            validateSlot(dto.getTimeSlotStart(), dto.getTimeSlotEnd());
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setPointId(dto.getPointId());
        update.setPointSnapshot(point == null ? null : snapshot(point));
        if (changeSlot) {
            update.setTimeSlotStart(LocalDateTime.parse(dto.getTimeSlotStart(), FMT));
            update.setTimeSlotEnd(LocalDateTime.parse(dto.getTimeSlotEnd(), FMT));
        }
        update.setRescheduleCount((order.getRescheduleCount() == null ? 0 : order.getRescheduleCount()) + 1);
        orderMapper.updateById(update);
        log.info("[order][updatePoint][{}] orderNo={} 面交点改为 {}（第 {} 次调整）",
                userId, order.getOrderNo(), point == null ? dto.getPointId() : point.getName(),
                update.getRescheduleCount());
        return toVOList(List.of(Objects.requireNonNull(orderMapper.selectById(orderId))), userId).get(0);
    }

    /** 商品变得不可交易（下架/过期/成交）时，作废该商品上所有进行中面交单；返回影响行数 */
    @Transactional(rollbackFor = Exception.class)
    public int closeActiveOrders(Long itemId, String reason) {
        if (itemId == null) {
            return 0;
        }
        int rows = orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                .eq(MeetupOrder::getItemId, itemId)
                .in(MeetupOrder::getStatus, STATUS_ACTIVE)
                .set(MeetupOrder::getStatus, STATUS_INVALID)
                .set(MeetupOrder::getCancelReason, reason));
        if (rows > 0) {
            log.info("[order][close-active][item={}] 作废 {} 张进行中的面交单：{}", itemId, rows, reason);
        }
        return rows;
    }

    // ================================================================ 内部 API（admin / platform / item 调用）

    /** 内部：订单详情（无归属校验，仅 /inner/** 暴露） */
    public OrderVO innerDetail(Long orderId) {
        MeetupOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        return toVOList(List.of(order), null).get(0);
    }

    /** 内部：管理端订单分页（userId 为空=全量；userId 非空=该用户作为买家或卖家的单） */
    public PageResult<OrderVO> innerPage(Long userId, Integer status, String keyword,
                                         long pageNum, long pageSize) {
        var w = Wrappers.<MeetupOrder>lambdaQuery()
                .eq(status != null, MeetupOrder::getStatus, status)
                .and(StringUtils.hasText(keyword), q -> q.like(MeetupOrder::getOrderNo, keyword)
                        .or().like(MeetupOrder::getItemTitle, keyword))
                .orderByDesc(MeetupOrder::getId);
        if (userId != null) {
            w.and(q -> q.eq(MeetupOrder::getBuyerId, userId).or().eq(MeetupOrder::getSellerId, userId));
        }
        Page<MeetupOrder> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), w);
        return PageResult.of(toVOList(page.getRecords(), null), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 内部：admin 处置（强制改状态；3=完成会置商品售出、4/5=失效会释放商品锁，均 best-effort） */
    @GlobalTransactional(name = "meetup-admin-status")
    @Transactional(rollbackFor = Exception.class)
    public boolean innerUpdateStatus(Long orderId, Integer status, String reason) {
        MeetupOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (status == null || status < STATUS_PENDING || status > 7) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法的面交单状态：" + status);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(status);
        if (StringUtils.hasText(reason)) {
            update.setCancelReason(reason);
        }
        int rows = orderMapper.updateById(update);
        if (status == STATUS_DONE) {
            markItemSoldBestEffort(order);
        } else if (status == STATUS_CANCELLED || status == STATUS_INVALID) {
            releaseItemBestEffort(order);
        }
        return rows > 0;
    }

    /**
     * 内部建单（admin/platform 等内部调用）：
     * platformOrder=true 或带 dealPrice → 平台款（条件扣库存+status=2 待自提）；否则生成个人意向单。
     * 外层带全局事务注解，保证平台分支（跨库写 item）也在 Seata 全局事务内。
     */
    @GlobalTransactional(name = "inner-create-order")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> innerCreate(InnerCreateOrderDTO dto) {
        if (dto == null || dto.getBuyerId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少买家 id");
        }
        if (Boolean.TRUE.equals(dto.getPlatformOrder()) || dto.getDealPrice() != null) {
            return createPlatformOrder(dto);
        }
        CreateOrderDTO c = new CreateOrderDTO();
        c.setItemId(dto.getItemId());
        c.setQuantity(dto.getQuantity());
        c.setRemark(dto.getRemark());
        return createSingle(dto.getBuyerId(), c);
    }

    /** 内部：订单统计 {total,today,completed}（admin 概览用） */
    public Map<String, Object> stats() {
        long total = orderMapper.selectCount(null);
        long today = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .ge(MeetupOrder::getCreatedAt, LocalDate.now().atStartOfDay()));
        long completed = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getStatus, STATUS_DONE));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", total);
        map.put("today", today);
        map.put("completed", completed);
        return map;
    }

    // ---------------------------------------------------------------- 平台代卖（platform-service 调用）

    /**
     * 内部：平台代卖下单（platform-service 直购/抢购成功后调；与第 5 轮 platformBuy 语义一致）。
     * 平台单：seller_id=平台账号(默认 1000)、seller_confirm_at=now、status=2 待自提；平台款条件扣库存。
     */
    @GlobalTransactional(name = "platform-buy")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createPlatformOrder(InnerCreateOrderDTO dto) {
        if (dto == null || dto.getBuyerId() == null || dto.getItemId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少买家或商品");
        }
        Long buyerId = dto.getBuyerId();
        Long itemId = dto.getItemId();
        InnerItemVO item = loadItem(itemId);
        Long platformSellerId = dto.getSellerId() != null ? dto.getSellerId() : props.getPlatformSellerId();
        boolean platformItem = (item.getSellerType() != null && item.getSellerType() == 2)
                || Objects.equals(item.getSellerId(), platformSellerId);
        if (!platformItem) {
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
        int qty = dto.getQuantity() == null || dto.getQuantity() < 1 ? 1 : dto.getQuantity();
        int stock = item.getStock() == null ? 0 : item.getStock();
        if (stock < qty) {
            throw new BizException(ResultCode.PARAM_ERROR, "库存不足（剩余 " + stock + " 件）");
        }
        BigDecimal price = dto.getDealPrice() != null ? dto.getDealPrice() : item.getPrice();
        // 平台款：条件扣库存（item-service 内部接口，0 行=已售完/库存不足）
        Result<Integer> lockRes;
        try {
            lockRes = itemClient.lock(itemId, qty);
        } catch (Exception e) {
            log.error("[order][platform-buy] 扣库存失败 item={}: {}", itemId, e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (lockRes == null || lockRes.getCode() != ResultCode.SUCCESS.getCode()) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    lockRes == null || lockRes.getMessage() == null ? "商品服务暂不可用" : lockRes.getMessage());
        }
        if (lockRes.getData() == null || lockRes.getData() != 1) {
            throw new BizException(ResultCode.ITEM_LOCKED, "这件平台商品已售完或库存不足，看看同款？");
        }

        MeetupOrder order = new MeetupOrder();
        order.setOrderNo(genOrderNo());
        order.setBuyerId(buyerId);
        order.setSellerId(platformSellerId);
        order.setItemId(itemId);
        order.setItemTitle(item.getTitle());
        order.setItemCover(item.getCoverUrl());
        order.setQuantity(qty);
        order.setDealPrice(price == null ? BigDecimal.ZERO : price);
        order.setPointId(dto.getPointId());
        order.setStatus(props.getPlatformItemStatus());
        order.setRescheduleCount(0);
        order.setSellerConfirmAt(LocalDateTime.now());
        order.setRemark("平台代卖自提单");
        orderMapper.insert(order);
        publishCreated(order);
        log.info("[order][platform-buy][{}] orderNo={} item={} qty={} price={} 平台单已扣库存",
                buyerId, order.getOrderNo(), itemId, qty, price);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderId", order.getId());
        result.put("orderNo", order.getOrderNo());
        result.put("itemTitle", order.getItemTitle());
        result.put("itemCover", order.getItemCover());
        result.put("dealPrice", order.getDealPrice());
        result.put("pointId", order.getPointId());
        result.put("quantity", order.getQuantity());
        result.put("status", order.getStatus());
        return result;
    }

    /** 内部：平台订单分页（seller_id=平台账号） */
    public PageResult<OrderVO> platformPage(Integer status, String keyword, long pageNum, long pageSize) {
        var w = Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getSellerId, props.getPlatformSellerId())
                .eq(status != null, MeetupOrder::getStatus, status)
                .and(StringUtils.hasText(keyword), q -> q.like(MeetupOrder::getOrderNo, keyword)
                        .or().like(MeetupOrder::getItemTitle, keyword))
                .orderByDesc(MeetupOrder::getId);
        Page<MeetupOrder> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), w);
        return PageResult.of(toVOList(page.getRecords(), null), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 内部：平台订单概览 {total,gmv,pendingPickup,completed}。
     * campusId 仅作参数接收：meetup_order 不存 campusId，跨库又禁止 JOIN item —— 本轮返回全站口径（报告注明）。
     */
    public Map<String, Object> platformSummary(Long campusId) {
        Long platformId = props.getPlatformSellerId();
        long total = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getSellerId, platformId));
        long pendingPickup = orderMapper.selectCount(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getSellerId, platformId).eq(MeetupOrder::getStatus, STATUS_WAITING));
        List<MeetupOrder> completedOrders = orderMapper.selectList(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getSellerId, platformId).eq(MeetupOrder::getStatus, STATUS_DONE)
                .select(MeetupOrder::getDealPrice));
        long completed = completedOrders.size();
        BigDecimal gmv = completedOrders.stream()
                .map(MeetupOrder::getDealPrice).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", total);
        map.put("gmv", gmv);
        map.put("pendingPickup", pendingPickup);
        map.put("completed", completed);
        if (campusId != null) {
            map.put("campusIdIgnored", true);
        }
        return map;
    }

    // ================================================================ 数据访问辅助

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

    private boolean isPlatformOrder(MeetupOrder order) {
        return order != null && props.getPlatformSellerId() != null
                && props.getPlatformSellerId().equals(order.getSellerId());
    }

    // ---------------------------------------------------------------- Feign 调用（含降级）

    /** 查商品：失败抛业务异常（商品不存在 30001 / 商品服务不可用 90001） */
    private InnerItemVO loadItem(Long itemId) {
        Result<InnerItemVO> result;
        try {
            result = itemClient.get(itemId);
        } catch (Exception e) {
            log.error("[order][feign][item={}] 调 item-service 失败: {}", itemId, e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            if (result != null && result.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
                throw new BizException(ResultCode.ITEM_NOT_FOUND);
            }
            String msg = result == null || result.getMessage() == null ? "商品服务暂不可用" : result.getMessage();
            throw new BizException(ResultCode.SYSTEM_ERROR, msg);
        }
        if (result.getData() == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return result.getData();
    }

    /** 购物车结算用：商品不存在返回 null（走 skipped），商品服务不可用抛异常（不能让用户误以为商品都没了） */
    private InnerItemVO loadItemForCheckout(Long itemId) {
        Result<InnerItemVO> result;
        try {
            result = itemClient.get(itemId);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (result == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (result.getCode() == ResultCode.SUCCESS.getCode()) {
            return result.getData();
        }
        if (result.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
            return null;
        }
        if (result.getCode() == CODE_SERVICE_UNAVAILABLE) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        throw new BizException(ResultCode.SYSTEM_ERROR,
                result.getMessage() == null ? "商品服务暂不可用" : result.getMessage());
    }

    /** 查商品：任何失败都返回 null（列表/详情/改点校验的降级场景） */
    private InnerItemVO loadItemOrNull(Long itemId) {
        try {
            Result<InnerItemVO> result = itemClient.get(itemId);
            if (result != null && result.getCode() == ResultCode.SUCCESS.getCode()) {
                return result.getData();
            }
        } catch (Exception e) {
            log.warn("[order][feign][item={}] 商品服务不可用（降级）: {}", itemId, e.getMessage());
        }
        return null;
    }

    /** 查点位：map-service 不可用时返回 null（降级，不阻塞下单/改点） */
    private InnerPointVO loadPointOrNull(Long pointId) {
        if (pointId == null) {
            return null;
        }
        try {
            Result<InnerPointVO> result = pointClient.get(pointId);
            if (result != null && result.getCode() == ResultCode.SUCCESS.getCode()) {
                return result.getData();
            }
            if (result != null && result.getCode() != CODE_SERVICE_UNAVAILABLE) {
                log.warn("[order][feign][point={}] 点位查询失败 code={}", pointId, result.getCode());
            } else {
                log.warn("[order][feign][point={}] map-service 未就绪，点位降级无快照", pointId);
            }
        } catch (Exception e) {
            log.warn("[order][feign][point={}] map-service 不可用（降级）: {}", pointId, e.getMessage());
        }
        return null;
    }

    private Map<Long, InnerUserVO> batchUsers(Collection<Long> userIds) {
        Map<Long, InnerUserVO> map = new HashMap<>();
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return map;
        }
        try {
            Result<List<InnerUserVO>> r = userClient.batch(ids.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
            if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                r.getData().forEach(u -> map.put(u.getId(), u));
                return map;
            }
        } catch (Exception e) {
            log.warn("[order][users] 批量取用户失败: {}", e.getMessage());
        }
        for (Long id : ids) {
            try {
                Result<InnerUserVO> r = userClient.get(id);
                if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                    map.put(id, r.getData());
                }
            } catch (Exception ignore) {
                // 降级：昵称/信用留空
            }
        }
        return map;
    }

    private Map<Long, InnerPointVO> batchPoints(Collection<Long> pointIds) {
        Map<Long, InnerPointVO> map = new HashMap<>();
        for (Long pointId : pointIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new))) {
            InnerPointVO p = loadPointOrNull(pointId);
            if (p != null) {
                map.put(pointId, p);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- 商品侧写操作（Seata AT / 降级）

    /**
     * 面交锁库（accept 用）。
     * <ul>
     *   <li>item-service 明确返回 0 → 商品已被别人锁走/已售出：本单置失效（独立事务）并抛 30004；</li>
     *   <li>fallback/异常（90001）→ 降级返回 false，调用方继续把订单置待面交并 log.warn
     *       （报告遗留：item-service 面交锁库接口未就绪期间商品未真正锁库）；</li>
     *   <li>data=1 → 已锁库，true。</li>
     * </ul>
     */
    private boolean lockItemForMeetup(MeetupOrder order, LocalDateTime expireAt) {
        Result<Integer> r;
        try {
            // item-service 侧注解 @DateTimeFormat(iso = DATE_TIME)，必须传 "yyyy-MM-dd'T'HH:mm:ss"
            r = itemClient.meetupLock(order.getItemId(), order.getBuyerId(),
                    expireAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (Exception e) {
            log.warn("[order][accept][degrade] 面交锁库异常 item={}（降级：订单置待面交但商品未锁库）: {}",
                    order.getItemId(), e.getMessage());
            return false;
        }
        if (r == null) {
            log.warn("[order][accept][degrade] 面交锁库无响应 item={}", order.getItemId());
            return false;
        }
        if (r.getCode() != ResultCode.SUCCESS.getCode()) {
            log.warn("[order][accept][degrade] 面交锁库接口不可用 item={} code={} msg={}（降级：商品未锁库）",
                    order.getItemId(), r.getCode(), r.getMessage());
            return false;
        }
        Integer data = r.getData();
        if (data == null || data == 0) {
            // 明确失败：商品已被别人锁走/已售出 → 本单置失效（独立事务，避免被下面的异常一起回滚）
            orderStateRecorder.invalidate(order.getId(), order.getSellerId(),
                    "商品已被其他同学约走或已售出");
            throw new BizException(ResultCode.ITEM_LOCKED, "这件商品已被其他同学锁定或已售出，无法确认面交");
        }
        return true;
    }

    /** 释放商品锁（cancel/reject/admin 处置用；best-effort，失败只记 warn） */
    private void releaseItemBestEffort(MeetupOrder order) {
        try {
            Result<Integer> r = itemClient.meetupRelease(order.getItemId());
            if (r == null || r.getCode() != ResultCode.SUCCESS.getCode()) {
                log.warn("[order][release][degrade] 面交解锁接口不可用 item={}（商品可能仍处已锁定）", order.getItemId());
            }
        } catch (Exception e) {
            log.warn("[order][release][degrade] 面交解锁异常 item={}: {}", order.getItemId(), e.getMessage());
        }
    }

    /** 商品置已售出（卖家成交/admin 完成用；best-effort，失败只记 warn） */
    private void markItemSoldBestEffort(MeetupOrder order) {
        try {
            Result<Integer> r = itemClient.meetupSold(order.getItemId());
            if (r == null || r.getCode() != ResultCode.SUCCESS.getCode()) {
                log.warn("[order][sold][degrade] 商品成交接口不可用 item={}（商品可能未置售出）", order.getItemId());
            } else if (r.getData() != null && r.getData() == 0) {
                log.warn("[order][sold][degrade] 商品成交接口影响 0 行 item={}（状态已变化）", order.getItemId());
            }
        } catch (Exception e) {
            log.warn("[order][sold][degrade] 商品成交异常 item={}: {}", order.getItemId(), e.getMessage());
        }
    }

    /** 平台单完成收口（按剩余库存；接口未就绪时仅记 warn） */
    private void platformSettleBestEffort(MeetupOrder order) {
        try {
            Result<Integer> r = itemClient.platformSettle(order.getItemId());
            if (r == null || r.getCode() != ResultCode.SUCCESS.getCode()) {
                log.warn("[order][platform-settle][degrade] 平台收口接口不可用 item={}", order.getItemId());
            }
        } catch (Exception e) {
            log.warn("[order][platform-settle][degrade] 平台收口异常 item={}: {}", order.getItemId(), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 事件

    private void publishCreated(MeetupOrder order) {
        eventPublisher.publishEvent(new OrderCreatedEvent(order.getId(), order.getOrderNo(),
                order.getItemId(), order.getBuyerId(), order.getQuantity(), FMT.format(LocalDateTime.now())));
    }

    private void publishCreatedEvents(List<CartItem> created, String batchNo, Long buyerId) {
        // 购物车合并结算：逐单发 order.created（与第 5 轮语义一致，item-service 消费累加 sale_count）
        if (created == null || created.isEmpty()) {
            return;
        }
        // created 里只有购物车行信息，订单对象已在上游插入；这里按 batchNo 精确查回，保证事件与订单对应
        List<MeetupOrder> orders = orderMapper.selectList(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getBatchNo, batchNo).eq(MeetupOrder::getBuyerId, buyerId));
        orders.forEach(this::publishCreated);
    }

    // ---------------------------------------------------------------- VO 组装

    private List<OrderVO> toVOList(List<MeetupOrder> orders, Long currentUserId) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = new ArrayList<>();
        List<Long> pointIds = new ArrayList<>();
        for (MeetupOrder o : orders) {
            userIds.add(o.getBuyerId());
            userIds.add(o.getSellerId());
            if (o.getPointId() != null) {
                pointIds.add(o.getPointId());
            }
        }
        Map<Long, InnerUserVO> users = batchUsers(userIds);
        Map<Long, InnerPointVO> points = batchPoints(pointIds);
        return orders.stream().map(o -> toVO(o, currentUserId, users, points)).toList();
    }

    private OrderVO toVO(MeetupOrder o, Long currentUserId,
                         Map<Long, InnerUserVO> users, Map<Long, InnerPointVO> points) {
        OrderVO vo = new OrderVO();
        vo.setId(o.getId());
        vo.setOrderId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setBatchNo(o.getBatchNo());
        vo.setItemId(o.getItemId());
        vo.setItemTitle(o.getItemTitle());
        vo.setItemCover(o.getItemCover());
        vo.setQuantity(o.getQuantity());
        vo.setDealPrice(o.getDealPrice());
        vo.setPointId(o.getPointId());
        InnerPointVO point = points.get(o.getPointId());
        vo.setPointName(point == null ? null : point.getName());
        vo.setPointGuide(point == null ? null : point.guideText());
        vo.setTimeSlotStart(o.getTimeSlotStart());
        vo.setTimeSlotEnd(o.getTimeSlotEnd());
        vo.setStatus(o.getStatus());
        vo.setStatusText(Texts.orderStatus(o.getStatus()));
        vo.setBuyerId(o.getBuyerId());
        vo.setSellerId(o.getSellerId());
        InnerUserVO buyer = users.get(o.getBuyerId());
        InnerUserVO seller = users.get(o.getSellerId());
        vo.setBuyerName(buyer == null ? null : buyer.getNickname());
        vo.setSellerName(seller == null ? null
                : (seller.getShopName() != null ? seller.getShopName() : seller.getNickname()));
        vo.setRemark(o.getRemark());
        vo.setCancelReason(o.getCancelReason());
        vo.setCreatedAt(o.getCreatedAt());
        vo.setVerifiedAt(o.getVerifiedAt());
        vo.setBuyerConfirmed(o.getBuyerConfirmAt() != null);
        vo.setSellerConfirmed(o.getSellerConfirmAt() != null);
        if (currentUserId != null) {
            vo.setMyRole(Objects.equals(o.getBuyerId(), currentUserId) ? "buyer" : "seller");
        }
        return vo;
    }

    /** 下单回执：{orderId, orderNo, sellerId, pointName}（与单体 orderBrief 一致） */
    private Map<String, Object> orderBrief(MeetupOrder o) {
        InnerPointVO p = loadPointOrNull(o.getPointId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("sellerId", o.getSellerId());
        m.put("pointName", p == null ? null : p.getName());
        return m;
    }

    private String snapshot(InnerPointVO p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", p.getId());
        map.put("name", p.getName());
        map.put("guideText", p.guideText());
        map.put("safetyScore", p.getSafetyScore());
        map.put("lng", p.longitude());
        map.put("lat", p.latitude());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- 工具

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
        if (Duration.between(s, e).toMinutes() > props.getMaxSlotMinutes()) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID,
                    "单次面交时间窗不能超过 " + (props.getMaxSlotMinutes() / 60) + " 小时");
        }
        if (s.isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID, "开始时间不能早于当前时间");
        }
        if (s.isAfter(LocalDateTime.now().plusDays(props.getMaxSlotDays()))) {
            throw new BizException(ResultCode.TIME_SLOT_INVALID,
                    "最多只能预约 " + props.getMaxSlotDays() + " 天内的面交");
        }
    }

    private String genOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String genBatchNo() {
        return "B" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
    }

    /** 单号/状态文案工具（旧代码调用方在别处仍可能用） */
    public static String statusText(Integer status) {
        return Texts.orderStatus(status);
    }
}
