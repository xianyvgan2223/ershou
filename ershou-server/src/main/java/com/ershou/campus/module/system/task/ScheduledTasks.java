package com.ershou.campus.module.system.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import com.ershou.campus.module.search.service.ItemIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 定时任务：释放过期锁库、面交单超时失效、商品过期、浏览量回写。
 * 多实例部署时需换成 ShedLock 或 XXL-Job（开发文档 7.10），浏览量回写尤其要防重复执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final ItemMapper itemMapper;
    private final MeetupOrderMapper orderMapper;
    private final ItemIndexService itemIndexService;
    private final StringRedisTemplate redis;
    private final CacheEvictor evictor;

    /** 每分钟：释放超时未确认的锁库，让商品回到在售 */
    @Scheduled(cron = "0 * * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void releaseExpiredLocks() {
        List<Item> locked = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .eq(Item::getStatus, 3)
                .isNotNull(Item::getLockExpireAt)
                .lt(Item::getLockExpireAt, LocalDateTime.now())
                .select(Item::getId));
        if (locked.isEmpty()) {
            return;
        }
        int count = 0;
        List<Long> released = new ArrayList<>(locked.size());
        for (Item item : locked) {
            if (itemMapper.releaseLock(item.getId()) > 0) {
                count++;
                released.add(item.getId());
            }
        }
        TxHooks.afterCommit(() -> itemIndexService.syncByIds(released));
        TxHooks.afterCommit(() -> evictor.itemsChanged(released));
        log.info("[task][release-lock] 释放 {} 件商品的锁库", count);
    }

    /** 每 5 分钟：卖家 2 小时未确认的面交单自动失效，并释放锁库 */
    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void expirePendingOrders() {
        List<MeetupOrder> pending = orderMapper.selectList(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getStatus, 1)
                .lt(MeetupOrder::getCreatedAt, LocalDateTime.now().minusHours(2)));
        List<Long> restocked = new ArrayList<>(pending.size());
        for (MeetupOrder order : pending) {
            MeetupOrder update = new MeetupOrder();
            update.setId(order.getId());
            update.setStatus(5);
            update.setCancelReason("卖家超时未确认，自动失效");
            orderMapper.updateById(update);
            if (itemMapper.releaseLock(order.getItemId()) > 0) {
                restocked.add(order.getItemId());
            }
        }
        TxHooks.afterCommit(() -> itemIndexService.syncByIds(restocked));
        TxHooks.afterCommit(() -> evictor.itemsChanged(restocked));
        if (!pending.isEmpty()) {
            log.info("[task][expire-order] {} 张面交单超时失效", pending.size());
        }
    }

    /** 每天 03:00：商品有效期到期自动过期 */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void expireItems() {
        List<Item> expired = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .eq(Item::getStatus, 2)
                .isNotNull(Item::getExpireAt)
                .lt(Item::getExpireAt, LocalDateTime.now())
                .select(Item::getId));
        List<Long> expiredIds = new ArrayList<>(expired.size());
        for (Item item : expired) {
            Item update = new Item();
            update.setId(item.getId());
            update.setStatus(7);
            itemMapper.updateById(update);
            expiredIds.add(item.getId());
        }
        TxHooks.afterCommit(() -> itemIndexService.syncByIds(expiredIds));
        TxHooks.afterCommit(() -> evictor.itemsChanged(expiredIds));
        if (!expired.isEmpty()) {
            log.info("[task][expire-item] {} 件商品过期", expired.size());
        }
    }

    /**
     * 每 10 分钟：把 Redis 里的浏览量增量批量回写 item.view_count（开发文档 6.2 / 7 任务表）。
     *
     * <p>替代原先"每次看详情就 UPDATE 一次"的写法：热点商品不再反复锁同一行，
     * 也不会因为 updated_at 被频繁刷新而让 ES 增量同步反复重推同一批商品。
     *
     * <p>顺序很关键：先全部写 DB（同一事务），成功之后再扣减 Redis 计数。
     * 任何一步失败都只是"下一轮重试"，不会丢计数（DB 回滚时 Redis 还没动）。
     */
    @Scheduled(cron = "${ershou.cache.view-flush-cron:0 */10 * * * ?}")
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
        // DB 已落库，这里只扣减增量；扣完仍 > 0 说明期间又有新浏览，留在集合里下一轮继续
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
