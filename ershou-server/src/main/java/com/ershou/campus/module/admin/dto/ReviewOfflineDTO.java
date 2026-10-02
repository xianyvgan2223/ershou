package com.ershou.campus.module.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 上架审核页「下架」请求体 */
@Data
public class ReviewOfflineDTO {

    @Schema(description = "下架原因（选填，留空按默认文案记）")
    private String reason;
}
