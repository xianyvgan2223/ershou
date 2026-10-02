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
    /** 1=允许发送（canSend 的判据）。**不要**拿它表示"已删除"，见 deletedA/deletedB */
    private Integer status;

    /**
     * 每用户软删除：某侧把这个会话从<b>自己的列表</b>里删掉了（「删除聊天记录」）。
     *
     * <p>一条会话是两个人共用的，直接 DELETE 整行会连对方的会话和历史入口一起删掉，
     * 所以按人记标记。对方再发消息时发送路径会把对应标记置回 0（会话重新出现）——
     * 否则消息落库了、这侧却永远看不到这个会话，等于丢消息。
     *
     * <p>⚠️ 字段名必须与列名严格对应：{@code deletedAtA → deleted_at_a}。
     * 一开始写成 {@code deletedAAt}，MyBatis-Plus 映射成了 {@code deleted_at_a}，
     * 与建表的 {@code deleted_a_at} 对不上，结果**所有读会话的接口全部 90001**。
     * 这类错只有真跑一次接口才会暴露（编译期完全正常）。
     */
    private Integer deletedA;
    private Integer deletedB;
    private LocalDateTime deletedAAt;
    private LocalDateTime deletedBAt;

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

    /** 我这一侧是否已删除（已删除的会话不出现在我的列表与未读汇总里） */
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
