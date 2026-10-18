package com.ershou.campus.module.platform.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 运营台 · 优惠券保存入参（设计 3.3 `POST/PUT /coupons`）。
 * 字段与 Web `api/platform-console.ts#ConsoleCouponSaveParams` 对齐。
 *
 * <p>时间用 String 接收（前端日期选择器/浏览器 JSON 格式不一致，见 {@code PlatformTimeUtils}）；
 * campusId 缺省时按设计取默认校区 2。
 */
@Data
public class CouponSaveDTO {

    /** 不传 = 默认校区 2 */
    private Long campusId;

    @NotBlank(message = "券名称不能为空")
    private String name;

    /** 1=满减 2=折扣 */
    @NotNull(message = "券类型不能为空")
    private Integer type;

    /** 满减门槛（type=1） */
    private BigDecimal thresholdAmount;

    /** 减免金额（type=1） */
    private BigDecimal discountAmount;

    /** 折扣率（type=2）：0.90 = 9 折；也兼容前端传 90 */
    private BigDecimal discountRate;

    @NotNull(message = "总库存不能为空")
    @Min(value = 1, message = "总库存至少 1")
    private Integer totalStock;

    @NotNull(message = "每人限领不能为空")
    @Min(value = 1, message = "每人限领至少 1")
    private Integer perUserLimit;

    @NotBlank(message = "开始时间不能为空")
    private String startAt;

    @NotBlank(message = "结束时间不能为空")
    private String endAt;

    /** 1=启用 0=停用；新建缺省 1，编辑空则保持原状态 */
    private Integer status;
}
