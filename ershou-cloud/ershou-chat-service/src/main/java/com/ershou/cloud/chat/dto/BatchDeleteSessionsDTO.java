package com.ershou.cloud.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 批量删除聊天记录（会话列表多选删除） */
@Data
public class BatchDeleteSessionsDTO {

    @NotNull(message = "ids 不能为空")
    private List<Long> ids;
}
