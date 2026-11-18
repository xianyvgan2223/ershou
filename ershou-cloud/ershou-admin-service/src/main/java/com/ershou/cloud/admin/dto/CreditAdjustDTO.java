package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 信用分人工调整 */
@Data
public class CreditAdjustDTO {
    @NotNull(message = "请填写分数变化")
    private Integer changeValue;
    @Size(max = 200, message = "原因不能超过 200 字")
    private String reason;
}
