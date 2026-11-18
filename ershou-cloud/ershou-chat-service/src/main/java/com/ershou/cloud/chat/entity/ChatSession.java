package com.ershou.cloud.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 私聊会话。表结构约定 user_a &lt; user_b + item_id 唯一（uk_session_pair_item），
 * "同一对人 + 同一件商品"只有一条会话。
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
    /** 1=允许发送（canSend 的判据）。不要拿它表示"已删除"，见 deletedA/deletedB */
    private Integer status;

    /** 每用户软删除：我这一侧删掉了会话（对方再发消息时自动恢复） */
    private Integer deletedA;
    private Integer deletedB;
    private LocalDateTime deletedAAt;
    private LocalDateTime deletedBAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean contains(Long userId) {
        return userA != null && userA.equals(userId) || userB != null && userB.equals(userId);
    }

    public Long peerOf(Long userId) {
        return userA != null && userA.equals(userId) ? userB : userA;
    }

    public boolean deletedFor(Long userId) {
        return nz(userA != null && userA.equals(userId) ? deletedA : deletedB) == 1;
    }

    public Integer unreadOf(Long userId) {
        int value = userA != null && userA.equals(userId) ? nz(unreadA) : nz(unreadB);
        return value;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
