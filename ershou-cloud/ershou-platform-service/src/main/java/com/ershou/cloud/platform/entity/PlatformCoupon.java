package com.ershou.cloud.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台优惠券模板（设计 2.2）。本轮只做领取，不接下单抵扣（见设计第八节）。
 */
@Data
@TableName("platform_coupon")
public class PlatformCoupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campusId;
    private String name;
    /** 1=满减 2=折扣 */
    private Integer type;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    /** 折扣率：0.90 = 9 折（type=2 时用） */
    private BigDecimal discountRate;
    private Integer totalStock;
    private Integer perUserLimit;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** 1=启用 0=停用 */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
