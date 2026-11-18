package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 上架审核页的批量处置（一次确认/下架多条） */
@Data
public class BatchReviewActionDTO {
    @NotNull(message = "ids 不能为空")
    @Size(max = 50, message = "单次最多处置 50 条")
    private List<Long> ids;
    /** 下架原因（action=offline 时必填） */
    private String reason;
}
