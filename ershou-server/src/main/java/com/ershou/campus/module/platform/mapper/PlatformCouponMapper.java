package com.ershou.campus.module.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.platform.entity.PlatformCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlatformCouponMapper extends BaseMapper<PlatformCoupon> {

    /** 运营台概览：某校区券的总库存（无券返回 0） */
    @Select("SELECT COALESCE(SUM(total_stock), 0) FROM platform_coupon WHERE campus_id = #{campusId}")
    long sumTotalStockByCampus(@Param("campusId") Long campusId);
}
