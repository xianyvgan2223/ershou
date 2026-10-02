package com.ershou.campus.module.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 上架审核页的批量处置（一次确认/下架多条） */
@Data
public class BatchReviewActionDTO {

    @Schema(description = "商品 id 列表")
    @NotNull(message = "ids 不能为空")
    private List<Long> ids;

    /** 下架原因（action=offline 时必填，会写入审核记录备注并展示在操作日志里） */
    @Schema(description = "下架原因，action=offline 时必填")
    private String reason;
}
