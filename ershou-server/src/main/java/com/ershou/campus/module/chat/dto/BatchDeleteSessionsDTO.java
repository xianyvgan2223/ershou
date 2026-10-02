package com.ershou.campus.module.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 批量删除聊天记录（会话列表多选删除） */
@Data
public class BatchDeleteSessionsDTO {

    @Schema(description = "要删除的会话 id 列表")
    @NotNull(message = "ids 不能为空")
    private List<Long> ids;
}
