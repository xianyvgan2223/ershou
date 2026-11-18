package com.ershou.cloud.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.platform.config.PlatformCacheKeys;
import com.ershou.cloud.platform.entity.PlatformConsignPoint;
import com.ershou.cloud.platform.entity.PlatformCoupon;
import com.ershou.cloud.platform.entity.PlatformFlashSale;
import com.ershou.cloud.platform.entity.PlatformFlashSaleItem;
import com.ershou.cloud.platform.feign.ItemClient;
import com.ershou.cloud.platform.feign.OrderClient;
import com.ershou.cloud.platform.feign.UserClient;
import com.ershou.cloud.platform.feign.model.InnerOrderVO;
import com.ershou.cloud.platform.feign.model.InnerUserVO;
import com.ershou.cloud.platform.feign.model.PlatformItemSummaryVO;
import com.ershou.cloud.platform.feign.model.PlatformOrderSummaryVO;
import com.ershou.cloud.platform.mapper.PlatformConsignPointMapper;
import com.ershou.cloud.platform.mapper.PlatformCouponMapper;
import com.ershou.cloud.platform.mapper.PlatformCouponUserMapper;
import com.ershou.cloud.platform.mapper.PlatformFlashSaleItemMapper;
import com.ershou.cloud.platform.mapper.PlatformFlashSaleMapper;
import com.ershou.cloud.platform.util.PlatformTimeUtils;
import com.ershou.cloud.platform.vo.AdminPlatformOrderVO;
import com.ershou.cloud.platform.vo.PlatformAdminOverviewVO;
import com.ershou.cloud.platform.vo.PlatformCacheStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 运营台 · 聚合服务（设计 3.1 / 3.5 / 3.6；全量拆分版）。
 *
 * <p>只做只读聚合，不写业务数据：
 * <ul>
 *   <li>概览统计：平台商品计数来自 item-service（/inner/items/platform/summary），
 *       平台订单/GMV 来自 order-service（/inner/orders/platform/summary），
 *       点位/券/秒杀为本库数据；</li>
 *   <li>平台订单分页：order-service /inner/orders/platform，买家昵称批量走 user-service，
 *       自提点名在本库 platform_consign_point 补全；</li>
 *   <li>Redis 缓存统计：SCAN {@code ershou:platform:*}（不用 KEYS），String 给值 / Hash 给 size。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformConsoleService {

    /** 默认校区（设计：campusId 缺省按 2 处理） */
    private static final long DEFAULT_CAMPUS_ID = 2L;
    /** 库存告警阈值：在售平台商品 stock <= 2（与 item-service summary 的默认口径一致） */
    private static final int LOW_STOCK_THRESHOLD = 2;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PLATFORM_KEY_PATTERN = "ershou:platform:*";

    private final ItemClient itemClient;
    private final OrderClient orderClient;
    private final UserClient userClient;
    private final PlatformConsignPointMapper pointMapper;
    private final PlatformCouponMapper couponMapper;
    private final PlatformCouponUserMapper couponUserMapper;
    private final PlatformFlashSaleMapper flashSaleMapper;
    private final PlatformFlashSaleItemMapper flashSaleItemMapper;
    private final PlatformCacheService cacheService;
    private final StringRedisTemplate redis;

    // ---------------------------------------------------------------- 3.1 概览

    /** 运营台概览统计（口径见设计 3.1） */
    public PlatformAdminOverviewVO overview(Long campusId) {
        Long effectiveCampusId = campusId == null ? DEFAULT_CAMPUS_ID : campusId;
        PlatformAdminOverviewVO vo = new PlatformAdminOverviewVO();

        // 平台商品：item-service 汇总（不可用时降级 0，不阻塞运营台其余数字）
        Result<PlatformItemSummaryVO> itemSummary = itemClient.platformSummary(effectiveCampusId);
        if (itemSummary != null && itemSummary.getCode() == ResultCode.SUCCESS.getCode()
                && itemSummary.getData() != null) {
            PlatformItemSummaryVO data = itemSummary.getData();
            vo.setPlatformItemOnSale(data.resolveOnSale());
            vo.setPlatformItemTotal(data.resolveTotal());
            vo.setLowStockItems(data.resolveLowStock());
        } else {
            vo.setPlatformItemOnSale(0L);
            vo.setPlatformItemTotal(0L);
            vo.setLowStockItems(0L);
            log.warn("[platform][console] 平台商品汇总不可用（降级 0）：{}",
                    itemSummary == null ? null : itemSummary.getMessage());
        }

        // 点位/券：本库
        vo.setPointCount(zeroIfNull(pointMapper.selectCount(Wrappers.<PlatformConsignPoint>lambdaQuery()
                .eq(PlatformConsignPoint::getCampusId, effectiveCampusId)
                .eq(PlatformConsignPoint::getStatus, 1))));
        vo.setCouponCount(zeroIfNull(couponMapper.selectCount(Wrappers.<PlatformCoupon>lambdaQuery()
                .eq(PlatformCoupon::getCampusId, effectiveCampusId))));
        vo.setCouponTotalStock(couponMapper.sumTotalStockByCampus(effectiveCampusId));
        vo.setCouponClaimed(couponUserMapper.countByCampus(effectiveCampusId));

        // 秒杀：本库场次 + Redis 实时剩余
        int stockTotal = 0;
        int stockLeft = 0;
        int sold = 0;
        PlatformFlashSale scene = flashSaleMapper.selectCurrent(effectiveCampusId);
        if (scene != null) {
            vo.setFlashSceneId(scene.getId());
            vo.setFlashSceneTitle(scene.getTitle());
            vo.setFlashSceneStatus(scene.getStatus());
            List<PlatformFlashSaleItem> items = flashSaleItemMapper.selectList(
                    Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                            .eq(PlatformFlashSaleItem::getFlashSaleId, scene.getId()));
            for (PlatformFlashSaleItem item : items) {
                int itemStock = item.getSeckillStock() == null ? 0 : item.getSeckillStock();
                int itemSold = item.getSoldCount() == null ? 0 : item.getSoldCount();
                stockTotal += itemStock;
                sold += itemSold;
                int left = cacheService.seckillLeftStock(item.getItemId());
                if (left < 0) {
                    left = Math.max(itemStock - itemSold, 0);
                }
                stockLeft += left;
            }
            if (scene.getEndAt() != null) {
                long hours = Duration.between(LocalDateTime.now(), scene.getEndAt()).toHours();
                vo.setExpiringFlashHours(Math.max(hours, 0L));
            }
        }
        vo.setFlashStockTotal(stockTotal);
        vo.setFlashStockLeft(stockLeft);
        vo.setFlashSold(sold);

        // 平台订单：order-service 汇总（不可用时降级 0）
        Result<PlatformOrderSummaryVO> orderSummary = orderClient.platformSummary(effectiveCampusId);
        if (orderSummary != null && orderSummary.getCode() == ResultCode.SUCCESS.getCode()
                && orderSummary.getData() != null) {
            PlatformOrderSummaryVO data = orderSummary.getData();
            vo.setOrderTotal(zeroIfNull(data.getTotal()));
            vo.setOrderToday(zeroIfNull(data.getOrderToday()));
            vo.setGmvTotal(money(data.resolveGmv()));
            vo.setGmvToday(money(data.getGmvToday()));
            vo.setPendingPickup(zeroIfNull(data.getPendingPickup()));
            vo.setWaitingConfirm(zeroIfNull(data.getWaitingConfirm()));
        } else {
            vo.setOrderTotal(0L);
            vo.setOrderToday(0L);
            vo.setGmvTotal(BigDecimal.ZERO);
            vo.setGmvToday(BigDecimal.ZERO);
            vo.setPendingPickup(0L);
            vo.setWaitingConfirm(0L);
            log.warn("[platform][console] 平台订单汇总不可用（降级 0）：{}",
                    orderSummary == null ? null : orderSummary.getMessage());
        }
        return vo;
    }

    // ---------------------------------------------------------------- 3.5 订单

    /** 平台订单分页（order-service 只返回 seller_id=平台账号 的订单；买昵称/点名在本服务补齐） */
    public PageResult<AdminPlatformOrderVO> orders(Integer status, String keyword, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        Result<PageResult<InnerOrderVO>> result = orderClient.platformOrders(status, kw, num, size);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            log.warn("[platform][console][orders] 订单服务不可用，返回空页：{}",
                    result == null ? null : result.getMessage());
            return PageResult.of(List.of(), 0, num, size);
        }
        PageResult<InnerOrderVO> page = result.getData();
        List<InnerOrderVO> orders = page.getRecords() == null ? List.of() : page.getRecords();
        if (orders.isEmpty()) {
            return PageResult.of(List.of(), page.getTotal(), num, size);
        }
        Map<Long, String> buyerNames = buyerNames(orders.stream()
                .map(InnerOrderVO::getBuyerId).filter(Objects::nonNull).distinct().toList());
        Map<Long, String> pointNames = pointNames(orders.stream()
                .map(InnerOrderVO::getPointId).filter(Objects::nonNull).distinct().toList());
        List<AdminPlatformOrderVO> records = new ArrayList<>(orders.size());
        for (InnerOrderVO order : orders) {
            AdminPlatformOrderVO vo = new AdminPlatformOrderVO();
            vo.setId(order.getId());
            vo.setOrderNo(order.getOrderNo());
            vo.setItemId(order.getItemId());
            vo.setItemTitle(order.getItemTitle());
            vo.setItemCover(order.getItemCover());
            vo.setBuyerId(order.getBuyerId());
            vo.setBuyerNickname(buyerNames.get(order.getBuyerId()));
            vo.setQuantity(order.getQuantity());
            vo.setDealPrice(order.getDealPrice());
            vo.setStatus(order.getStatus());
            vo.setStatusText(StringUtils.hasText(order.getStatusText())
                    ? order.getStatusText() : orderStatusText(order.getStatus()));
            vo.setPointId(order.getPointId());
            vo.setPointName(pointNames.getOrDefault(order.getPointId(), order.getPointName()));
            vo.setCreatedAt(parseTime(order.getCreatedAt()));
            records.add(vo);
        }
        return PageResult.of(records, page.getTotal(), page.getPageNum() > 0 ? page.getPageNum() : num,
                page.getPageSize() > 0 ? page.getPageSize() : size);
    }

    // ---------------------------------------------------------------- 3.6 缓存

    /**
     * Redis 缓存统计（只读）：SCAN {@code ershou:platform:*}，
     * String 给值、Hash 给 field 数、其它类型给 null；TTL 为秒（-1 永不过期 / -2 已不存在）。
     */
    public PlatformCacheStatsVO cacheStats() {
        Set<String> keys = new TreeSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(PLATFORM_KEY_PATTERN).count(200).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("[platform][cache-stats] SCAN 失败（返回空列表）：{}", e.getMessage());
        }
        List<PlatformCacheStatsVO.CacheKeyItem> keyItems = new ArrayList<>(keys.size());
        for (String key : keys) {
            PlatformCacheStatsVO.CacheKeyItem item = new PlatformCacheStatsVO.CacheKeyItem();
            item.setKey(key);
            try {
                DataType type = redis.type(key);
                String typeName = type == null ? "none" : type.code();
                item.setType(typeName);
                item.setTtl(redis.getExpire(key));
                if ("string".equals(typeName)) {
                    item.setValue(redis.opsForValue().get(key));
                } else if ("hash".equals(typeName)) {
                    item.setValue(redis.opsForHash().size(key));
                }
            } catch (Exception e) {
                item.setType("unknown");
                item.setTtl(-2L);
                log.warn("[platform][cache-stats] 读取 key 失败 key={}：{}", key, e.getMessage());
            }
            keyItems.add(item);
        }
        PlatformCacheStatsVO vo = new PlatformCacheStatsVO();
        vo.setKeys(keyItems);
        vo.setTotalKeys(keyItems.size());
        try {
            vo.setWarmupLockHeld(Boolean.TRUE.equals(redis.hasKey(PlatformCacheKeys.warmupLock())));
        } catch (Exception e) {
            vo.setWarmupLockHeld(false);
        }
        return vo;
    }

    // ---------------------------------------------------------------- 内部

    private Map<Long, String> buyerNames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Result<List<InnerUserVO>> result = userClient.users(ids);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            log.warn("[platform][console] 用户昵称批量查询不可用（降级 null）：{}",
                    result == null ? null : result.getMessage());
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (InnerUserVO user : result.getData()) {
            if (user != null && user.getId() != null) {
                names.put(user.getId(), StringUtils.hasText(user.getShopName())
                        ? user.getShopName() : user.getNickname());
            }
        }
        return names;
    }

    private Map<Long, String> pointNames(List<Long> pointIds) {
        if (pointIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (PlatformConsignPoint point : pointMapper.selectBatchIds(pointIds)) {
            names.put(point.getId(), point.getName());
        }
        return names;
    }

    private LocalDateTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return PlatformTimeUtils.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 面交单状态文案（与旧单体 OrderService.statusText 完全一致，前端零改动） */
    public static String orderStatusText(Integer status) {
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
            default -> "";
        };
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
