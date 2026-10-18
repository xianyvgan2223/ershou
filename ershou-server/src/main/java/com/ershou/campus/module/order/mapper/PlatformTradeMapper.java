package com.ershou.campus.module.order.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 平台代卖交易专用 SQL（第 2 轮平台代卖 <b>v1.1</b>，order-member 所有）。
 *
 * <p>为什么单独建一个 Mapper 而不是往 {@link com.ershou.campus.module.item.mapper.ItemMapper} 里加：
 * {@code item.seller_type}/{@code item.platform_point_id} 是平台模块迁移脚本（{@code sql/migration-platform.sql}）
 * 新增的列，属于 item 模块的写范围；交易侧只在<b>下单/完成/取消</b>三个动作上需要碰它们，
 * 放在这里可以把"平台交易"的 SQL 集中在一处，也不和 item 模块并行开发冲突。
 *
 * <p><b>平台商品不锁库（v1.1 修正）</b>：个人商品用 {@code status=3} 锁库（同一时间只允许一笔交易），
 * 但平台多库存商品（秒杀 5 份）必须允许<b>并发成交</b> —— 所以这里只做<b>条件扣库存</b>：
 * {@code stock = stock - 数量}，库存归零时置 {@code status=4}。
 * 全程<b>不写</b> {@code status=3 / lock_user_id / lock_expire_at}：否则秒杀会退化成"一次只能卖一份"，
 * 还会被 status=3 的锁过期定时任务（{@code ScheduledTasks#releaseExpiredLocks}）误伤。
 *
 * <p><b>防超卖的唯一闸门</b>是 {@link #decrementPlatformStock} 的条件更新
 * （{@code WHERE seller_type=2 AND status=2 AND stock>=?}）：InnoDB 行锁保证并发下只有库存足够的人能扣成功，
 * 影响 0 行 = 已售完 / 库存不足 / 不是平台商品，调用方按失败处理（抛 {@code ITEM_LOCKED}）。
 *
 * <p>注意：这里假设迁移脚本已经把 {@code item.seller_type} 列加上（默认 1=个人，2=平台）。
 * 迁移未落库时这些 SQL 会报 Unknown column，属于<b>预期</b>的依赖顺序（平台模块负责迁移，QA 负责落库）。
 */
@Mapper
public interface PlatformTradeMapper {

    /**
     * 平台商品标识：1=个人 2=平台。用于下单前的友好校验（真正的兜底在 {@link #decrementPlatformStock} 的 WHERE 里）。
     * 商品不存在时返回 null。
     */
    @Select("SELECT seller_type FROM item WHERE id = #{itemId}")
    Integer selectSellerType(@Param("itemId") Long itemId);

    /**
     * 平台代卖点名称（给订单列表用）。
     *
     * <p>为什么需要它：平台单的 {@code meetup_order.point_id} 存的是 {@code platform_consign_point.id}，
     * 不是 {@code trade_point.id}（设计 2.3 / 3.5）。{@code OrderService#toVO} 原来一律拿 trade_point 解释，
     * 平台单会命中同 id 的<b>无关点位数</b>，在「我的面交」里显示成别的地名 ——
     * 这里按平台点表取名字，避免把错的地名展示给买家。
     */
    @Select("SELECT name FROM platform_consign_point WHERE id = #{pointId}")
    String selectPlatformPointName(@Param("pointId") Long pointId);

    /**
     * 条件扣库存（平台单防超卖，v1.1 用扣库存代替锁库）。
     *
     * <p><b>SET 赋值顺序很关键</b>：MySQL 单表 UPDATE 的赋值是<b>从左到右</b>取值的
     * （项目里 {@code SysUserMapper#addCredit} 的注释踩过同一个坑），所以必须
     * <b>先算 status、再扣 stock</b>。照抄设计文档里"stock 先减、IF(stock-?&lt;=0)"的顺序时，
     * {@code IF} 里读到的是扣减后的库存 —— 例如 stock=5 买 3 件会误判成售罄（剩余 2 件被吞掉）。
     * 这里保持文档 3.5 的表达式不变、只调换赋值顺序，语义 = "本次扣完刚好耗尽 → 4，否则回 2"。
     */
    @Update("UPDATE item SET status = IF(stock - #{quantity} <= 0, 4, 2), "
            + "stock = stock - #{quantity}, version = version + 1 "
            + "WHERE id = #{itemId} AND seller_type = 2 AND status = 2 AND stock >= #{quantity}")
    int decrementPlatformStock(@Param("itemId") Long itemId, @Param("quantity") Integer quantity);

    /**
     * 平台单完成：商品按剩余库存收口（v1.1）。
     *
     * <p>{@code stock>0 → 回到在售(2)} 继续卖，{@code stock=0 → 已售出(4)}。
     * <b>不碰 stock</b>，也不用 {@code ItemMapper.markSold}（它会无条件 {@code stock=0}，
     * 多库存平台商品（秒杀 5 份）的剩余份数会被清掉）。
     * {@code WHERE status IN (2,4)} 是为了排除已下架(5)/违规下架(8)/过期(7)的商品，
     * 不能因为买家确认收货就把一件已被运营处置的商品"复活"。
     */
    @Update("UPDATE item SET status = IF(stock > 0, 2, 4), version = version + 1 "
            + "WHERE id = #{itemId} AND seller_type = 2 AND status IN (2, 4)")
    int settlePlatformItem(@Param("itemId") Long itemId);

    /**
     * 平台单取消/拒绝：把下单时扣掉的库存还回去（v1.1）。
     *
     * <p>商品可能因为库存归零已经被置成 4，恢复库存后要回在售 2；
     * {@code status IN (2,4)} 同样排除已下架的商品（下架商品的库存不还原，避免把它复活）。
     * 数量来自面交单的 quantity（下单时已夹取 ≥1）。
     */
    @Update("UPDATE item SET status = 2, stock = stock + #{quantity}, version = version + 1 "
            + "WHERE id = #{itemId} AND seller_type = 2 AND status IN (2, 4)")
    int restorePlatformStock(@Param("itemId") Long itemId, @Param("quantity") Integer quantity);
}
