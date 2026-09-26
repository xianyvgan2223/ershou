package com.ershou.campus.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私聊会话。表结构约定 user_a &lt; user_b + item_id 唯一（uk_session_pair_item），
 * 这样"同一对人 + 同一件商品"只有一条会话，不用做去重扫描。
 */
@Data
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 较小的用户 id */
    private Long userA;
    /** 较大的用户 id */
    private Long userB;
    /** 来源商品（从商品详情发起会话时必填） */
    private Long itemId;
    private Long lastMsgId;
    /** 会话列表预览（短摘要，正文另存 chat_message.content） */
    private String lastMsgText;
    private LocalDateTime lastMsgAt;
    /** user_a 的未读数（user_b 发送时 +1） */
    private Integer unreadA;
    /** user_b 的未读数 */
    private Integer unreadB;
    /** 会话风险等级：0 正常 / 1 提醒 / 2 可疑 / 3 高危 */
    private Integer riskLevel;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 是否包含某个用户 */
    public boolean contains(Long userId) {
        return userA != null && userA.equals(userId) || userB != null && userB.equals(userId);
    }

    /** 取会话中的另一方 */
    public Long peerOf(Long userId) {
        return userA != null && userA.equals(userId) ? userB : userA;
    }

    public Integer unreadOf(Long userId) {
        int value = userA != null && userA.equals(userId) ? nz(unreadA) : nz(unreadB);
        return value;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
