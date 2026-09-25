package com.ershou.campus.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderDTO {
    @NotNull(message = "缺少商品")
    private Long itemId;

    private Integer quantity = 1;

    @NotNull(message = "请选择面交点")
    private Long pointId;

    @NotNull(message = "请选择开始时间")
    private String timeSlotStart;

    @NotNull(message = "请选择结束时间")
    private String timeSlotEnd;

    private String remark;
}
