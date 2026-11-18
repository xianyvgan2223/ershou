package com.ershou.cloud.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 聊天消息。content：文本消息 AES 加密存储（BR-12 留存要求），图片/卡片类存原文。 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long senderId;
    private Long receiverId;
    private Long itemId;
    /** 1=文本 2=图片 3=位置 4=商品卡片 5=面交单卡片 6=系统消息 */
    private Integer msgType;
    private String content;
    /** 0 正常 / 1 提醒 / 2 可疑 / 3 高危 */
    private Integer riskLevel;
    /** 是否被风控拦截（拦截的消息仍留痕） */
    private Integer blocked;
    /** 是否已撤回 */
    private Integer recalled;
    private LocalDateTime createdAt;
}
