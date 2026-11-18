package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 需要填写原因的后台操作（如强制下架） */
@Data
public class ReasonDTO {
    @NotBlank(message = "请填写原因")
    @Size(max = 200, message = "原因不能超过 200 字")
    private String reason;
}
