package com.ershou.cloud.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.platform.entity.PlatformCouponUser;
import com.ershou.cloud.platform.vo.MyCouponVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PlatformCouponUserMapper extends BaseMapper<PlatformCouponUser> {

    /** 我的券列表：DB 为准（含券模板信息与领取/使用状态） */
    @Select("SELECT cu.id, cu.coupon_id, c.name, c.type, c.threshold_amount, c.discount_amount, "
            + "c.discount_rate, cu.status, c.start_at, c.end_at, cu.received_at, cu.used_at, cu.order_id "
            + "FROM platform_coupon_user cu "
            + "JOIN platform_coupon c ON c.id = cu.coupon_id "
            + "WHERE cu.user_id = #{userId} "
            + "ORDER BY cu.received_at DESC, cu.id DESC")
    List<MyCouponVO> selectMyCoupons(@Param("userId") Long userId);

    /** 运营台概览：某校区券的已领取总量（JOIN 券模板按校区过滤） */
    @Select("SELECT COUNT(*) FROM platform_coupon_user cu "
            + "JOIN platform_coupon c ON c.id = cu.coupon_id "
            + "WHERE c.campus_id = #{campusId}")
    long countByCampus(@Param("campusId") Long campusId);
}
