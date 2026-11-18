package com.ershou.cloud.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户已领的优惠券（设计 2.2）。
 *
 * <p>唯一键 {@code (coupon_id, user_id)} 是"每人限领"的 DB 兜底：
 * Redis Hash 限领只是快路径，真正防重复的是这条唯一约束（见迁移脚本）。
 */
@Data
@TableName("platform_coupon_user")
public class PlatformCouponUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long couponId;
    private Long userId;
    /** 1=未用 2=已用 3=过期 */
    private Integer status;
    private LocalDateTime receivedAt;
    private LocalDateTime usedAt;
    /** 核销订单（本轮未接入抵扣，预留） */
    private Long orderId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
