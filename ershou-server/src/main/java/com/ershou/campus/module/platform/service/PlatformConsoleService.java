package com.ershou.campus.module.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.platform.config.PlatformCacheKeys;
import com.ershou.campus.module.platform.config.PlatformProperties;
import com.ershou.campus.module.platform.entity.PlatformConsignPoint;
import com.ershou.campus.module.platform.entity.PlatformCoupon;
import com.ershou.campus.module.platform.entity.PlatformFlashSale;
import com.ershou.campus.module.platform.entity.PlatformFlashSaleItem;
import com.ershou.campus.module.platform.mapper.PlatformConsignPointMapper;
import com.ershou.campus.module.platform.mapper.PlatformCouponMapper;
import com.ershou.campus.module.platform.mapper.PlatformCouponUserMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleItemMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleMapper;
import com.ershou.campus.module.platform.mapper.PlatformItemMapper;
import com.ershou.campus.module.platform.mapper.PlatformOrderMapper;
import com.ershou.campus.module.platform.vo.AdminPlatformOrderVO;
import com.ershou.campus.module.platform.vo.PlatformAdminOverviewVO;
import com.ershou.campus.module.platform.vo.PlatformCacheStatsVO;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 运营台 · 聚合服务（第 3 轮设计 3.1 / 3.5 / 3.6）。
 *
 * <p>只做只读聚合，不写业务数据：
 * <ul>
 *   <li>概览统计：商品/点位/券/秒杀/订单/GMV 一次查询拼装（数据量小，直接 count/sum，不走缓存，
 *       避免运营刚改完就看到旧数字）；</li>
 *   <li>平台订单分页：只查 {@code seller_id = 平台账号}，联买家昵称与平台点名；</li>
 *   <li>Redis 缓存统计：SCAN {@code ershou:platform:*}（不用 KEYS，避免阻塞），String 给值 / Hash 给 size。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformConsoleService {

    /** 默认校区（设计：campusId 缺省按 2 处理） */
    private static final long DEFAULT_CAMPUS_ID = 2L;
    /** 库存告警阈值：在售平台商品 stock <= 2 */
    private static final int LOW_STOCK_THRESHOLD = 2;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String PLATFORM_KEY_PATTERN = "ershou:platform:*";

    private final PlatformItemMapper itemMapper;
    private final PlatformConsignPointMapper pointMapper;
    private final PlatformCouponMapper couponMapper;
    private final PlatformCouponUserMapper couponUserMapper;
    private final PlatformFlashSaleMapper flashSaleMapper;
    private final PlatformFlashSaleItemMapper flashSaleItemMapper;
    private final PlatformOrderMapper orderMapper;
    private final PlatformCacheService cacheService;
    private final PlatformProperties platformProperties;
    private final StringRedisTemplate redis;

    // ---------------------------------------------------------------- 3.1 概览

    /** 运营台概览统计（口径见设计 3.1） */
    public PlatformAdminOverviewVO overview(Long campusId) {
        Long effectiveCampusId = campusId == null ? DEFAULT_CAMPUS_ID : campusId;
        Long sellerId = platformProperties.getSellerId();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        PlatformAdminOverviewVO vo = new PlatformAdminOverviewVO();
        vo.setPlatformItemOnSale(itemMapper.countOnSaleByCampus(effectiveCampusId));
        vo.setPlatformItemTotal(itemMapper.countByCampus(effectiveCampusId));
        vo.setPointCount(pointMapper.selectCount(Wrappers.<PlatformConsignPoint>lambdaQuery()
                .eq(PlatformConsignPoint::getCampusId, effectiveCampusId)
                .eq(PlatformConsignPoint::getStatus, 1)));
        vo.setCouponCount(couponMapper.selectCount(Wrappers.<PlatformCoupon>lambdaQuery()
                .eq(PlatformCoupon::getCampusId, effectiveCampusId)));
        vo.setCouponTotalStock(couponMapper.sumTotalStockByCampus(effectiveCampusId));
        vo.setCouponClaimed(couponUserMapper.countByCampus(effectiveCampusId));
        vo.setLowStockItems(itemMapper.countLowStockOnSale(effectiveCampusId, LOW_STOCK_THRESHOLD));

        // 当前进行中场次（含秒杀库存汇总）
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

        // 平台订单：只统计 seller_id=平台账号
        vo.setOrderToday(orderMapper.countSince(sellerId, todayStart));
        vo.setOrderTotal(orderMapper.countAll(sellerId));
        vo.setGmvToday(money(orderMapper.sumGmvSince(sellerId, todayStart)));
        vo.setGmvTotal(money(orderMapper.sumGmv(sellerId)));
        vo.setPendingPickup(orderMapper.countByStatus(sellerId, 2));
        vo.setWaitingConfirm(orderMapper.countByStatus(sellerId, 1));
        return vo;
    }

    // ---------------------------------------------------------------- 3.5 订单

    /** 平台订单分页（只查 seller_id=平台账号；keyword 匹配订单号/商品标题） */
    public PageResult<AdminPlatformOrderVO> orders(Integer status, String keyword, long pageNum, long pageSize) {
        Long sellerId = platformProperties.getSellerId();
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        long total = orderMapper.countPlatformOrders(sellerId, status, kw);
        if (total <= 0) {
            return PageResult.of(List.of(), 0, num, size);
        }
        int offset = (int) Math.min((num - 1) * size, Integer.MAX_VALUE);
        List<AdminPlatformOrderVO> records = orderMapper.selectPlatformOrders(
                sellerId, status, kw, offset, (int) size);
        records.forEach(order -> order.setStatusText(OrderService.statusText(order.getStatus())));
        return PageResult.of(records, total, num, size);
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

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
