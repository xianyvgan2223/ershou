package com.ershou.campus.module.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 信用分人工调整 */
@Data
public class CreditAdjustDTO {
    @NotNull(message = "请填写分数变化")
    private Integer changeValue;
    private String reason;
}
