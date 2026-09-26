package com.ershou.campus.module.chat.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendMessageDTO {

    @NotNull(message = "会话 id 不能为空")
    private Long sessionId;

    /** 1=文本 2=图片；图片时 content 为图片 URL */
    private Integer msgType = 1;

    @Size(max = 500, message = "单条消息最多 500 字")
    private String content;

    /** 客户端本地消息 id（可选），服务端 ACK 时原样带回 */
    private String clientMsgId;
}
