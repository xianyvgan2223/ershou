package com.ershou.campus.module.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OpenSessionDTO {

    /** 从哪个商品发起会话（PRD FR-IM-01：进会话默认携带商品卡片） */
    @NotNull(message = "商品 id 不能为空")
    private Long itemId;
}
