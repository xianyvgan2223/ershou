package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 我的券 VO（设计 3.3「我的券列表」，字段与 Web `api/platform.ts#MyCouponVO` 对齐）。
 */
@Data
public class MyCouponVO {
    /** 领取记录 id（platform_coupon_user.id） */
    private Long id;
    private Long couponId;
    private String name;
    /** 1=满减 2=折扣 */
    private Integer type;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    /** 1=未用 2=已用 3=过期 */
    private Integer status;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private LocalDateTime receivedAt;
    private LocalDateTime usedAt;
    private Long orderId;
}
