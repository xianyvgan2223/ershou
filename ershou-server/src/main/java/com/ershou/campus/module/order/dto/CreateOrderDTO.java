package com.ershou.campus.module.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 单品直接约面交：面交点与时间窗都可不填（不填=待协商，私聊里定） */
@Data
public class CreateOrderDTO {
    @NotNull(message = "缺少商品")
    private Long itemId;

    private Integer quantity = 1;

    private Long pointId;

    private String timeSlotStart;

    private String timeSlotEnd;

    private String remark;
}
