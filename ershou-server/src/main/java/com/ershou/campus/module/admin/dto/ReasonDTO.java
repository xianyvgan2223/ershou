package com.ershou.campus.module.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 需要填写原因的后台操作（如强制下架） */
@Data
public class ReasonDTO {
    @NotBlank(message = "请填写原因")
    private String reason;
}
