package com.ershou.cloud.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.platform.entity.PlatformFlashSaleItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformFlashSaleItemMapper extends BaseMapper<PlatformFlashSaleItem> {

    /** 抢购时按商品 id 找到"当前进行中场次"里的那条秒杀商品（没有 = 未开始/已结束，返回 null） */
    @Select("SELECT fsi.* FROM platform_flash_sale_item fsi "
            + "JOIN platform_flash_sale fs ON fs.id = fsi.flash_sale_id "
            + "WHERE fsi.item_id = #{itemId} AND fs.status = 2 "
            + "AND fs.start_at <= NOW() AND fs.end_at > NOW() "
            + "ORDER BY fs.id DESC LIMIT 1")
    PlatformFlashSaleItem selectCurrentByItemId(@Param("itemId") Long itemId);

    /**
     * 已抢件数 +1（仅展示口径；库存真值在 Redis）。
     * 条件 {@code sold_count < seckill_stock} 防止展示值超过总库存。
     */
    @Update("UPDATE platform_flash_sale_item SET sold_count = sold_count + 1, updated_at = NOW() "
            + "WHERE id = #{id} AND sold_count < seckill_stock")
    int incrementSoldCount(@Param("id") Long id);
}
