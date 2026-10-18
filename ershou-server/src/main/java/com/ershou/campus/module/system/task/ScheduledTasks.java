package com.ershou.campus.module.system.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import com.ershou.campus.module.order.service.OrderService;
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
    private final TradePointService tradePointService;
    private final StringRedisTemplate redis;
    private final CacheEvictor evictor;
    /** 商品到期下架时，顺手收掉挂在该商品上的约面交单 */
    private final OrderService orderService;
    /** 取「商品可挂几天」用于文案（ershou.item.valid-days） */
    private final ItemService itemService;

    /**
     * 到期要下架的商品状态：还想卖、但还没卖成的都算。
     * 4 已售出留成交记录、5/7/8 已是终态，都不在列。
     */
    private static final List<Integer> EXPIRABLE_ITEM_STATUS = List.of(1, 2, 3, 6);

    /**
     * 每分钟：释放超时的锁库，让商品回到在售。
     *
     * <p>锁只会在卖家"接受约面交"时加上（锁到时间窗结束 +2h），所以这里释放 = 双方
     * 到点没成交。此时同一件商品上挂着的"待面交"单子也必须一起作废，否则它会永远
     * 停在待面交，卖家在列表里看着像是还有一笔交易没完成。
     */
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
        int expiredOrders = 0;
        for (Long itemId : released) {
            expiredOrders += orderMapper.update(null, Wrappers.<MeetupOrder>lambdaUpdate()
                    .eq(MeetupOrder::getItemId, itemId)
                    .eq(MeetupOrder::getStatus, 2)
                    .set(MeetupOrder::getStatus, 5)
                    .set(MeetupOrder::getCancelReason, "面交时间已过，双方未完成交易，自动失效"));
        }
        TxHooks.afterCommit(() -> itemIndexService.syncByIds(released));
        TxHooks.afterCommit(() -> evictor.itemsChanged(released));
        log.info("[task][release-lock] 释放 {} 件商品的锁库，作废 {} 张待面交单", count, expiredOrders);
    }

    /**
     * 每 5 分钟：卖家 48 小时没确认的约面交意向自动失效。
     *
     * <p>以前是 2 小时 —— 晚上 11 点约、第二天早上看就是"已失效"，太不近人情；
     * 而且现在提交意向不锁库，挂着也占不到商品，放宽没有副作用。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void expirePendingOrders() {
        List<MeetupOrder> pending = orderMapper.selectList(Wrappers.<MeetupOrder>lambdaQuery()
                .eq(MeetupOrder::getStatus, 1)
                .lt(MeetupOrder::getCreatedAt, LocalDateTime.now().minusHours(48)));
        for (MeetupOrder order : pending) {
            MeetupOrder update = new MeetupOrder();
            update.setId(order.getId());
            update.setStatus(5);
            update.setCancelReason("卖家超过 48 小时未确认，自动失效");
            orderMapper.updateById(update);
        }
        if (!pending.isEmpty()) {
            log.info("[task][expire-order] {} 张面交意向超时失效（未锁库，无需释放商品）", pending.size());
        }
    }

    /**
     * 每小时：商品有效期到期自动下架（默认 7 天，`ershou.item.valid-days` 可配）。
     *
     * <p>口径「未售出就下架」：只要还没成交（status≠4），到期一律置为 7 已过期，
     * 覆盖 1 审核中 / 2 在售 / 3 已锁定 / 6 审核未通过 —— 都是"还想卖但没卖成"的状态。
     * 4 已售出要留成交记录、5/7/8 已经是终态，都不动。
     *
     * <p>商品下架后挂在上面的约面交单必须一起作废（复用 {@code closeActiveOrders}），
     * 否则买家那边会留一张永远等不到见面的「待面交」单和一个永不消失的红点；
     * 其中 3 已锁定的最典型 —— 锁只在 status=3 时才释放，商品一变 7 就永远等不到。
     *
     * <p>跑成每小时而不是每天 03:00：7 天是硬规则，每天只跑一次最多会多挂一整天。
     */
    @Scheduled(cron = "${ershou.item.expire-cron:0 30 * * * ?}")
    @Transactional(rollbackFor = Exception.class)
    public void expireItems() {
        // 开发/演示期应急开关（ershou.item.validity-enabled=false）：整体跳过到期下架。
        // 放在最前面：不查库、不改状态、不作废挂单；log.debug 在 dev profile 下（com.ershou.campus=debug）可见。
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
            closedOrders += orderService.closeActiveOrders(item.getId(),
                    "商品已到 " + itemService.getValidDays() + " 天有效期自动下架，本次约面交自动作废");
        }
        TxHooks.afterCommit(() -> itemIndexService.syncByIds(expiredIds));
        TxHooks.afterCommit(() -> evictor.itemsChanged(expiredIds));
        log.info("[task][expire-item] {} 件商品过期自动下架，同时作废 {} 张挂单", expiredIds.size(), closedOrders);
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

    /**
     * 每天 03:10：清理"没有商品"的面交点。
     *
     * <p>地图上只应该出现"真的有东西可看"的点位。商品下架/售出后点位会空掉，
     * 攒着不删地图就会越来越像荒地（用户反馈过：一堆 0 件的点位）。
     *
     * <p>规则见 {@link TradePointService#cleanupEmptyPoints()}：点位上没有任何
     * "还有戏"的商品（草稿/审核中/在售/已锁定），且点位创建超过 7 天才删 ——
     * 刚新建的点位给足时间挂商品。
     */
    @Scheduled(cron = "0 10 3 * * ?")
    public void cleanupEmptyTradePoints() {
        int removed = tradePointService.cleanupEmptyPoints();
        if (removed > 0) {
            log.info("[task][cleanup-point] 清理 {} 个没有商品的点位", removed);
        }
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
