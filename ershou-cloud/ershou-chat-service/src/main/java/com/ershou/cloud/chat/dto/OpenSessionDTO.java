package com.ershou.cloud.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 从商品发起会话（已存在则复用） */
@Data
public class OpenSessionDTO {

    @NotNull(message = "商品 id 不能为空")
    private Long itemId;
}
