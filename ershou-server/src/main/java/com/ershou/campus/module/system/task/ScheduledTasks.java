package com.ershou.campus.module.system.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import com.ershou.campus.module.search.service.ItemIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时任务：释放过期锁库、面交单超时失效、商品过期。
 * 多实例部署时需换成 ShedLock 或 XXL-Job（开发文档 7.10）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final ItemMapper itemMapper;
    private final MeetupOrderMapper orderMapper;
    private final ItemIndexService itemIndexService;

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
        if (!expired.isEmpty()) {
            log.info("[task][expire-item] {} 件商品过期", expired.size());
        }
    }
}
