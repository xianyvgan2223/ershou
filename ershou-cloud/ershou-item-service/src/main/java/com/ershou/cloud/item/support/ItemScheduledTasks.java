package com.ershou.cloud.item.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.search.ItemIndexService;
import com.ershou.cloud.item.service.ItemService;
import com.ershou.cloud.item.support.cache.CacheEvictor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * item-service 定时任务（T102 从旧单体 {@code ScheduledTasks} 拆出商品域部分）。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>✅ 到期下架 {@link #expireItems()}：只写 item 表；挂单作废走 order-service inner（不可用降级）；</li>
 *   <li>✅ 浏览量回写 {@link #flushViewCounts()}：Redis 增量 → item.view_count；</li>
 *   <li>❌ 释放超时锁库：要同时改 meetup_order（ershou_order 库），归 order-service 负责（记遗留，已报 Lead）；</li>
 *   <li>❌ 清理空点位：归 map-service；❌ 面交意向超时：归 order-service。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemScheduledTasks {

    /** 到期要下架的状态：还想卖、但还没卖成的都算（4 已售出/5/7/8 终态不动） */
    private static final List<Integer> EXPIRABLE_ITEM_STATUS = List.of(1, 2, 3, 6);

    private final ItemMapper itemMapper;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;
    private final StringRedisTemplate redis;
    private final ItemService itemService;
    private final RemoteService remote;

    /**
     * 商品有效期到期自动下架（默认每小时 :30）。
     * 限时总开关关闭时整个任务跳过（开发期 ershou.item.validity-enabled=false）。
     */
    @Scheduled(cron = "${ershou.item.expire-cron:0 30 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void expireItems() {
        if (!itemService.isValidityEnabled()) {
            log.debug("[task][expire-item] 商品限时已关闭（ershou.item.validity-enabled=false），跳过本轮");
            return;
        }
        List<Item> expired = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .in(Item::getStatus, EXPIRABLE_ITEM_STATUS)
                .isNotNull(Item::getExpireAt)
                .lt(Item::getExpireAt, LocalDateTime.now())
                .select(Item::getId));
        if (expired.isEmpty()) {
            return;
        }
        List<Long> expiredIds = new ArrayList<>(expired.size());
        int closedOrders = 0;
        for (Item item : expired) {
            Item update = new Item();
            update.setId(item.getId());
            update.setStatus(7);
            // 条件更新兜并发（卖家刚好在这时候重新上架/下架都不该被覆盖）
            int rows = itemMapper.update(update, Wrappers.<Item>lambdaUpdate()
                    .eq(Item::getId, item.getId())
                    .in(Item::getStatus, EXPIRABLE_ITEM_STATUS));
            if (rows == 0) {
                continue;
            }
            expiredIds.add(item.getId());
            // 挂单作废：跨服务调用 order-service（不可用降级为 0，不阻塞到期下架）
            closedOrders += remote.closeActiveOrders(item.getId(),
                    "商品已到 " + itemService.getValidDays() + " 天有效期自动下架，本次约面交自动作废");
        }
        TxHooks.afterCommit(() -> itemIndexService.syncByIds(expiredIds));
        TxHooks.afterCommit(() -> evictor.itemsChanged(expiredIds));
        log.info("[task][expire-item] {} 件商品过期自动下架，同时作废 {} 张挂单", expiredIds.size(), closedOrders);
    }

    /**
     * 浏览量批量回写（Redis 增量 → DB，每 10 分钟）。
     * 顺序：先全部写 DB（同事务），成功后再扣减 Redis 计数；任何一步失败都只等下一轮重试，不丢计数。
     */
    @Scheduled(cron = "${ershou.item.view-flush-cron:0 */10 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void flushViewCounts() {
        String dirtyKey = RedisKeys.itemViewDirty();
        Set<String> dirty = redis.opsForSet().members(dirtyKey);
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        Map<Long, Long> deltas = new LinkedHashMap<>();
        List<String> stale = new ArrayList<>();
        for (String raw : dirty) {
            Long itemId = parseLong(raw);
            if (itemId == null) {
                stale.add(raw);
                continue;
            }
            long delta = parseLong(redis.opsForValue().get(RedisKeys.itemView(itemId)));
            if (delta <= 0) {
                stale.add(raw);
                continue;
            }
            deltas.put(itemId, delta);
        }
        if (!stale.isEmpty()) {
            redis.opsForSet().remove(dirtyKey, stale.toArray());
        }
        if (deltas.isEmpty()) {
            return;
        }
        List<Long> flushed = new ArrayList<>(deltas.size());
        long total = 0;
        for (Map.Entry<Long, Long> entry : deltas.entrySet()) {
            if (itemMapper.incrViewBy(entry.getKey(), entry.getValue().intValue()) > 0) {
                flushed.add(entry.getKey());
                total += entry.getValue();
            }
        }
        for (Map.Entry<Long, Long> entry : deltas.entrySet()) {
            String key = RedisKeys.itemView(entry.getKey());
            try {
                Long left = redis.opsForValue().decrement(key, entry.getValue());
                if (left == null || left <= 0) {
                    redis.delete(key);
                    redis.opsForSet().remove(dirtyKey, String.valueOf(entry.getKey()));
                }
            } catch (Exception e) {
                log.warn("[task][view-flush][{}] 扣减 Redis 计数失败，下轮重试：{}", entry.getKey(), e.getMessage());
            }
        }
        // 缓存里的 viewCount 现在偏低，直接删掉让下次读回源取最新值
        TxHooks.afterCommit(() -> evictor.items(flushed));
        log.info("[task][view-flush] 回写 {} 件商品浏览量（合计 {} 次）", flushed.size(), total);
    }

    /** 兼容旧占位：定时任务里不再释放锁库（归 order-service） */
    @Deprecated
    public static Duration noop() {
        return Duration.ZERO;
    }

    private static Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
