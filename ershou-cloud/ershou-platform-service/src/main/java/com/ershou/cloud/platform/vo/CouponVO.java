package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券 VO（设计 3.2，字段与 Web `api/platform.ts#CouponVO` 对齐）。
 *
 * <p>{@code leftStock} 实时读 Redis（未预热时回退 DB 计数）；{@code claimed} 按当前登录用户查 DB。
 */
@Data
public class CouponVO {
    private Long id;
    private String name;
    /** 1=满减 2=折扣 */
    private Integer type;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private Integer perUserLimit;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer status;
    private Integer totalStock;
    /** 剩余库存（实时读 Redis） */
    private Integer leftStock;
    /** 当前用户是否已领（未登录恒 false） */
    private Boolean claimed = false;
    /** 当前是否可以领取 */
    private Boolean claimable = false;
}
