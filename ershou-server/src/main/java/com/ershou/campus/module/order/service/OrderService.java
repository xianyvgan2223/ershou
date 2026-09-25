package com.ershou.campus.module.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.cart.entity.CartItem;
import com.ershou.campus.module.cart.service.CartService;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.order.dto.CheckoutDTO;
import com.ershou.campus.module.order.dto.CreateOrderDTO;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import com.ershou.campus.module.order.vo.OrderVO;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final CartService cartService;
    private final TradePointService tradePointService;
    private final UserService userService;
    private final SysUserMapper userMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ItemIndexService itemIndexService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int LOCK_MINUTES = 30;
    private static final int MAX_SLOT_MINUTES = 180;

    // ---------------------------------------------------------------- 创建

    /** 购物车合并结算：按卖家分组，每组一张面交单，共享 batchNo */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(Long buyerId, CheckoutDTO dto) {
        validateSlot(dto.getTimeSlotStart(), dto.getTimeSlotEnd());
        List<CartItem> cartItems = cartService.rawByIds(buyerId, dto.getCartIds());
        if (cartItems.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "购物车里没有可结算的商品");
        }
        String batchNo = genBatchNo();
        List<Map<String, Object>> created = new ArrayList<>();
        List<Long> usedCartIds = new ArrayList<>();
        for (CartItem ci : cartItems) {
            MeetupOrder order = doCreate(buyerId, ci.getItemId(), ci.getQuantity(),
                    dto.getPointId(), dto.getTimeSlotStart(), dto.getTimeSlotEnd(), dto.getRemark(), batchNo);
            created.add(orderBrief(order));
            usedCartIds.add(ci.getId());
        }
        cartService.removeByIds(usedCartIds);
        log.info("[order][checkout][{}] batch={} 生成 {} 张面交单", buyerId, batchNo, created.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orders", created);
        return result;
    }

    /** 单品直接约面交 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSingle(Long buyerId, CreateOrderDTO dto) {
        validateSlot(dto.getTimeSlotStart(), dto.getTimeSlotEnd());
        String batchNo = genBatchNo();
        MeetupOrder order = doCreate(buyerId, dto.getItemId(), dto.getQuantity(),
                dto.getPointId(), dto.getTimeSlotStart(), dto.getTimeSlotEnd(), dto.getRemark(), batchNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchNo", batchNo);
        result.put("orders", List.of(orderBrief(order)));
        return result;
    }

    private MeetupOrder doCreate(Long buyerId, Long itemId, Integer quantity, Long pointId,
                                 String start, String end, String remark, String batchNo) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (item.getStatus() != null && item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD);
        }
        TradePoint point = tradePointService.getById(pointId);
        if (point == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "面交点不存在");
        }
        // 条件更新锁库（DB 兜底）；失败说明已被他人锁定或商品已不在售
        int locked = itemMapper.lockStock(itemId, buyerId, LocalDateTime.now().plusMinutes(LOCK_MINUTES));
        if (locked == 0) {
            log.warn("[order][lock-fail][{}][{}] 商品已被锁定或不可售", buyerId, itemId);
            throw new BizException(ResultCode.ITEM_LOCKED);
        }
        // 2 在售 → 3 已锁定：同步搜索索引，避免锁库商品继续被搜到
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
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
        order.setPointSnapshot(snapshot(point));
        order.setTimeSlotStart(LocalDateTime.parse(start, FMT));
        order.setTimeSlotEnd(LocalDateTime.parse(end, FMT));
        order.setStatus(1);
        order.setVerifyCode(code);
        order.setVerifyExpireAt(LocalDateTime.parse(end, FMT).plusHours(2));
        order.setRescheduleCount(0);
        order.setRemark(remark);
        orderMapper.insert(order);

        Duration ttl = Duration.between(LocalDateTime.now(), order.getVerifyExpireAt());
        if (ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(180);
        }
        redis.opsForValue().set(RedisKeys.verifyCode(order.getId()), code, ttl);
        log.info("[order][create][{}] orderNo={} item={} 核销码已下发", buyerId, order.getOrderNo(), itemId);
        return order;
    }

    // ---------------------------------------------------------------- 查询

    public PageResult<OrderVO> page(Long userId, String role, long pageNum, long pageSize) {
        boolean asSeller = "seller".equalsIgnoreCase(role);
        Page<MeetupOrder> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<MeetupOrder>lambdaQuery()
                        .eq(asSeller ? MeetupOrder::getSellerId : MeetupOrder::getBuyerId, userId)
                        .orderByDesc(MeetupOrder::getId));
        return PageResult.of(page, o -> toVO(o, userId));
    }

    public OrderVO detail(Long orderId, Long userId) {
        return toVO(requireOrder(orderId, userId), userId);
    }

    // ---------------------------------------------------------------- 状态流转

    @Transactional(rollbackFor = Exception.class)
    public void accept(Long orderId, Long userId) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION, "只有卖家可以确认面交");
        }
        if (order.getStatus() != 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(2);
        orderMapper.updateById(update);
        log.info("[order][accept][{}] orderNo={}", userId, order.getOrderNo());
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long orderId, Long userId, String reason) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (!Objects.equals(order.getSellerId(), userId)) {
            throw new BizException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (order.getStatus() != 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(5);
        update.setCancelBy(userId);
        update.setCancelReason(StringUtils.hasText(reason) ? reason : "卖家拒绝了面交");
        orderMapper.updateById(update);
        itemMapper.releaseLock(order.getItemId());
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        log.info("[order][reject][{}] orderNo={} reason={}", userId, order.getOrderNo(), reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, Long userId, String reason) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() != 1 && order.getStatus() != 2) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(4);
        update.setCancelBy(userId);
        update.setCancelReason(StringUtils.hasText(reason) ? reason : "双方协商取消");
        orderMapper.updateById(update);
        itemMapper.releaseLock(order.getItemId());
        TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
        log.info("[order][cancel][{}] orderNo={}", userId, order.getOrderNo());
    }

    /** 核销：核销码一次性使用，DB 条件更新兜并发 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> verify(Long orderId, Long userId, String code) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() != 2) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前状态无需核销");
        }
        if (order.getVerifiedAt() != null) {
            throw new BizException(ResultCode.VERIFY_CODE_ERROR, "该核销码已使用");
        }
        if (order.getVerifyExpireAt() != null && order.getVerifyExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ResultCode.VERIFY_CODE_ERROR, "核销码已过期");
        }
        if (!StringUtils.hasText(code) || !code.trim().equals(order.getVerifyCode())) {
            throw new BizException(ResultCode.VERIFY_CODE_ERROR);
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = orderMapper.markVerified(orderId, userId, now);
        if (updated == 0) {
            throw new BizException(ResultCode.VERIFY_CODE_ERROR, "该核销码已被使用");
        }
        log.info("[order][verify][{}] orderNo={} 核销成功", userId, order.getOrderNo());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verifiedAt", now);
        result.put("needConfirm", true);
        return result;
    }

    /** 双向确认：双方都确认后面交单完成，商品置为已售出并加信用分 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirm(Long orderId, Long userId) {
        MeetupOrder order = requireOrder(orderId, userId);
        if (order.getStatus() != 2 && order.getStatus() != 3) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean isBuyer = Objects.equals(order.getBuyerId(), userId);
        LocalDateTime buyerAt = isBuyer ? now : order.getBuyerConfirmAt();
        LocalDateTime sellerAt = isBuyer ? order.getSellerConfirmAt() : now;

        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        if (isBuyer) {
            update.setBuyerConfirmAt(now);
        } else {
            update.setSellerConfirmAt(now);
        }
        boolean finished = buyerAt != null && sellerAt != null;
        if (finished) {
            update.setStatus(3);
        }
        orderMapper.updateById(update);

        if (finished && order.getStatus() != 3) {
            itemMapper.markSold(order.getItemId());
            TxHooks.afterCommit(() -> itemIndexService.syncById(order.getItemId()));
            userMapper.addCredit(order.getBuyerId(), 2);
            userMapper.addCredit(order.getSellerId(), 2);
            log.info("[order][finished][{}] orderNo={} 面交完成，双方信用分 +2", userId, order.getOrderNo());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", finished ? 3 : order.getStatus());
        result.put("message", finished ? "面交已完成，快去评价吧" : "已确认，等待对方确认");
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

    private void validateSlot(String start, String end) {
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
        map.put("coordX", p.getCoordX());
        map.put("coordY", p.getCoordY());
        try {
            return json.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> orderBrief(MeetupOrder o) {
        TradePoint p = tradePointService.getById(o.getPointId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("sellerId", o.getSellerId());
        m.put("pointName", p == null ? null : p.getName());
        m.put("lockExpireAt", LocalDateTime.now().plusMinutes(LOCK_MINUTES));
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
        vo.setCreatedAt(o.getCreatedAt());
        vo.setVerifiedAt(o.getVerifiedAt());
        vo.setBuyerId(o.getBuyerId());
        vo.setSellerId(o.getSellerId());
        vo.setBuyerConfirmed(o.getBuyerConfirmAt() != null);
        vo.setSellerConfirmed(o.getSellerConfirmAt() != null);
        vo.setMyRole(Objects.equals(o.getBuyerId(), currentUserId) ? "buyer" : "seller");
        // 核销码仅在待面交时对交易双方可见
        if (o.getStatus() == 2 && o.getVerifiedAt() == null) {
            vo.setVerifyCode(o.getVerifyCode());
        }
        TradePoint p = tradePointService.getById(o.getPointId());
        if (p != null) {
            vo.setPointName(p.getName());
            vo.setPointGuide(p.getGuideText());
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