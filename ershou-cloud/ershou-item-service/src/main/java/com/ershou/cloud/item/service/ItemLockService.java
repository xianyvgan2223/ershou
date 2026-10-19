package com.ershou.cloud.item.service;

import com.ershou.cloud.item.config.ItemCacheKeys;
import com.ershou.cloud.item.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内部库存锁定（order-service 经 OpenFeign 调用，设计 4.3/5.1）。
 *
 * <p>单条条件 UPDATE 防超卖；方法带 {@code @Transactional} 以参与 Seata AT 全局事务：
 * order-service 的 {@code @GlobalTransactional} 提交/回滚时，Seata 依据 {@code ershou_item.undo_log}
 * 里记录的前镜像自动还原 {@code stock}/{@code status}，本类**不写任何补偿逻辑**。
 *
 * <p>返回语义：{@code 1}=锁定成功；{@code 0}=失败（商品不存在/不在售/库存不足），不抛异常，便于 Feign 侧判空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemLockService {

    private final ItemMapper itemMapper;
    private final StringRedisTemplate redis;

    /**
     * 锁定库存。
     *
     * @param itemId 商品 id
     * @param qty    数量（<=0 或 null 按 1 处理）
     * @return 1 成功 / 0 失败
     */
    @Transactional(rollbackFor = Exception.class)
    public int lock(Long itemId, Integer qty) {
        int quantity = (qty == null || qty <= 0) ? 1 : qty;
        int rows = itemMapper.lockStock(itemId, quantity);
        if (rows == 0) {
            log.info("[inner][lock][{}] 锁定失败（不存在/不在售/库存不足），qty={}", itemId, quantity);
            return 0;
        }
        // 库存与状态已变，详情缓存必须失效（Redis db2）。即便外层全局事务随后回滚，
        // 多删一次缓存也无副作用：下次读会回源 DB 拿到回滚后的值。
        evictDetail(itemId);
        log.info("[inner][lock][{}] 锁定成功，扣减库存 {} 件（如归零则置已售出）", itemId, quantity);
        return 1;
    }

    /** 删除商品详情缓存（供锁定与 MQ 消费共用） */
    public void evictDetail(Long itemId) {
        try {
            redis.delete(ItemCacheKeys.itemDetail(itemId));
        } catch (Exception e) {
            log.warn("[inner][cache][{}] 清详情缓存失败（不影响业务）：{}", itemId, e.getMessage());
        }
    }
}
