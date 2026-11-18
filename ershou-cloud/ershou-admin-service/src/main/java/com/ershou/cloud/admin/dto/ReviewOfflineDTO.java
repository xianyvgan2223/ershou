package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 上架审核页「下架」请求体（选填，留空按默认文案记） */
@Data
public class ReviewOfflineDTO {
    @Size(max = 200, message = "原因不能超过 200 字")
    private String reason;
}
