package com.ershou.campus.module.order.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 购物车合并结算。
 *
 * <p>面交点与时间窗都<b>可不填</b>：不填表示"待协商"，约面交只需表达意向，
 * 具体在哪见、几点见，双方在私聊里定，卖家确认后商品才锁定。
 */
@Data
public class CheckoutDTO {
    @NotEmpty(message = "请选择要面交的商品")
    private List<Long> cartIds;

    private Long pointId;

    private String timeSlotStart;

    private String timeSlotEnd;

    private String remark;
}
