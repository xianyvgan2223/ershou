package com.ershou.cloud.chat.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 单条消息（对当前用户而言的视图） */
@Data
public class ChatMessageVO {

    private Long id;
    private Long sessionId;
    private Long senderId;
    private Long receiverId;
    private Integer msgType;
    private String content;
    private Integer riskLevel;
    private String riskWarning;
    private Boolean mine;
    private Boolean recalled;
    private String clientMsgId;
    private LocalDateTime createdAt;
}
