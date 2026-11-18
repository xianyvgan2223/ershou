package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 批量审核商品：action = approve / reject（与单体 DTO 一致） */
@Data
public class BatchItemAuditDTO {
    @NotEmpty(message = "请至少选择一件商品")
    private List<Long> ids;
    @NotBlank(message = "请指定审核动作")
    private String action;
    private String reasonCode;
    private String reason;
}
