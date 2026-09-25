package com.ershou.campus.module.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CheckoutDTO {
    @NotEmpty(message = "请选择要面交的商品")
    private List<Long> cartIds;

    @NotNull(message = "请选择面交点")
    private Long pointId;

    @NotNull(message = "请选择开始时间")
    private String timeSlotStart;

    @NotNull(message = "请选择结束时间")
    private String timeSlotEnd;

    private String remark;
}
