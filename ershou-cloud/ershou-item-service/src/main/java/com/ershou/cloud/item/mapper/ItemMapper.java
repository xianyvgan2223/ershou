package com.ershou.cloud.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.item.entity.Item;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper。读用 MyBatis-Plus {@link BaseMapper}；写只保留第 5 轮切片需要的两条条件更新。
 */
@Mapper
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 库存锁定（order-service 经 OpenFeign 调用）：单条 SQL 条件更新，防超卖。
     *
     * <p>条件 {@code status=2 AND stock>=qty}；影响行数 0 = 已被抢完/不在售/库存不足，调用方按"锁定失败"处理。
     * 库存扣到 0 时同步置 {@code status=4}（已售出）。
     *
     * <p><b>注意 SET 子句顺序</b>：MySQL 单表 UPDATE 的 SET 从左到右求值、后面的表达式看到的是**更新后**的值，
     * 所以 status 必须写在 stock 之前，这样 {@code stock - qty} 用的仍是旧库存（写反会恒判为 0 而误置已售出）。
     *
     * <p>参与 Seata AT：由 order-service 的 {@code @GlobalTransactional} 串起来，回滚时 Seata 用
     * {@code ershou_item.undo_log} 里记录的前镜像自动还原 stock/status，本方法**不需要**手写补偿。
     */
    @Update("UPDATE item SET status = CASE WHEN stock - #{qty} = 0 THEN 4 ELSE 2 END, stock = stock - #{qty} "
            + "WHERE id = #{itemId} AND status = 2 AND stock >= #{qty}")
    int lockStock(@Param("itemId") Long itemId, @Param("qty") int qty);

    /**
     * RabbitMQ 消费：累加已售件数。幂等由调用方的 Redis SETNX(orderId) 保证，这里只做原子累加。
     */
    @Update("UPDATE item SET sale_count = sale_count + #{qty} WHERE id = #{itemId}")
    int incrSaleCount(@Param("itemId") Long itemId, @Param("qty") int qty);
}
