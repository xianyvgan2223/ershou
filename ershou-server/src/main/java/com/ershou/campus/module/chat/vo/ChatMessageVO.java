package com.ershou.campus.module.chat.vo;

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
    /** 风控弱提醒文案（命中站外引流等可疑词时随消息一起返回，由前端弹提示；不篡改原文） */
    private String riskWarning;
    /** 是否本人发送 */
    private Boolean mine;
    /** 是否已撤回：true 时 content 为空，由前端渲染成"你/对方撤回了一条消息" */
    private Boolean recalled;
    /** 发送方本地消息 id：WS ACK 时回带，用于把"发送中"替换成"已送达" */
    private String clientMsgId;
    private LocalDateTime createdAt;
}
