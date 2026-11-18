package com.ershou.cloud.item.service;

import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.support.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内部库存操作（order-service 经 OpenFeign 调用；契约 §三 item-service）。
 *
 * <p>两类语义严格区分：
 * <ul>
 *   <li><b>平台款/秒杀扣库存</b> {@code lock(id,qty)}：只扣 stock，扣到 0 置已售出(4)，
 *       不写 lock_user_id —— 对应 {@code PUT /inner/items/{id}/lock?qty=}；</li>
 *   <li><b>面交锁库</b> {@code meetupLock/meetupRelease/meetupSold}：status 2→3→(2|4)，
 *       写 lock_user_id/lock_expire_at —— 对应 order-service 面交单状态机（T102 order-member 契约）。</li>
 * </ul>
 * 所有方法带 {@code @Transactional} 以作为 Seata AT 分支事务参与全局事务：
 * order-service 的 {@code @GlobalTransactional} 回滚时，Seata 依据 ershou_item.undo_log 自动还原，
 * 本类**不写任何补偿逻辑**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemLockService {

    private final ItemMapper itemMapper;
    private final StringRedisTemplate redis;

    /**
     * 平台商品扣库存（条件更新防超卖）。
     *
     * @param itemId 商品 id
     * @param qty    数量（<=0 或 null 按 1 处理）
     * @return 1 成功 / 0 失败（不存在/不在售/库存不足），不抛异常便于 Feign 判空
     */
    @Transactional(rollbackFor = Exception.class)
    public int lock(Long itemId, Integer qty) {
        int quantity = (qty == null || qty <= 0) ? 1 : qty;
        int rows = itemMapper.deductStock(itemId, quantity);
        if (rows == 0) {
            log.info("[inner][lock][{}] 扣库存失败（不存在/不在售/库存不足），qty={}", itemId, quantity);
            return 0;
        }
        evictDetail(itemId);
        log.info("[inner][lock][{}] 扣库存成功，扣减 {} 件（如归零则置已售出）", itemId, quantity);
        return 1;
    }

    /**
     * 面交锁库：status 2→3，写买家与锁到期时间（条件更新兜并发）。
     *
     * @return 1 成功 / 0 失败
     */
    @Transactional(rollbackFor = Exception.class)
    public int meetupLock(Long itemId, Long buyerId, java.time.LocalDateTime expireAt) {
        int rows = itemMapper.lockStock(itemId, buyerId, expireAt);
        if (rows == 0) {
            log.info("[inner][meetup-lock][{}] 锁库失败（不存在/已非在售），buyerId={}", itemId, buyerId);
            return 0;
        }
        evictDetail(itemId);
        log.info("[inner][meetup-lock][{}] 锁库成功 buyerId={} expireAt={}", itemId, buyerId, expireAt);
        return 1;
    }

    /** 面交释放锁：status 3→2，清买家与锁到期时间；返回 1/0 */
    @Transactional(rollbackFor = Exception.class)
    public int meetupRelease(Long itemId) {
        int rows = itemMapper.releaseLock(itemId);
        if (rows > 0) {
            evictDetail(itemId);
        }
        log.info("[inner][meetup-release][{}] 释放锁库 rows={}", itemId, rows);
        return rows > 0 ? 1 : 0;
    }

    /** 面交成交：status IN (2,3)→4，stock=0，清锁；返回 1/0 */
    @Transactional(rollbackFor = Exception.class)
    public int meetupSold(Long itemId) {
        int rows = itemMapper.markSold(itemId);
        if (rows > 0) {
            evictDetail(itemId);
        }
        log.info("[inner][meetup-sold][{}] 置已售出 rows={}", itemId, rows);
        return rows > 0 ? 1 : 0;
    }

    /** 删除商品详情缓存（供锁定与 MQ 消费共用；失败不影响业务） */
    public void evictDetail(Long itemId) {
        try {
            redis.delete(RedisKeys.itemDetail(itemId));
        } catch (Exception e) {
            log.warn("[inner][cache][{}] 清详情缓存失败（不影响业务）：{}", itemId, e.getMessage());
        }
    }
}
