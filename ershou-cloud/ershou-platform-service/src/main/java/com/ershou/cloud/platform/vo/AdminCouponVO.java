package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营台 · 优惠券行（GET /platform/admin/coupons）。
 * 字段与 Web `api/platform-console.ts#ConsoleAdminCouponVO` 对齐。
 */
@Data
public class AdminCouponVO {
    private Long id;
    private String name;
    /** 1=满减 2=折扣 */
    private Integer type;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    /** 折扣率：0.90 = 9 折 */
    private BigDecimal discountRate;
    private Integer totalStock;
    private Integer perUserLimit;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** 1=启用 0=停用 */
    private Integer status;
    private String statusText;
    private Long campusId;
    /** 已领取数量（DB 真值） */
    private Long claimedCount;
    /** 剩余库存（优先 Redis，未预热回退 totalStock-claimedCount） */
    private Integer leftStock;
}
