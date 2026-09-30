package com.ershou.campus.module.chat.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 会话列表项：对方信息 + 来源商品卡片 + 最后一条消息 + 未读数 */
@Data
public class ChatSessionVO {

    private Long id;
    /** 最后一条消息 id：客户端据此判断"这个会话有没有新消息"，避免每次都全量拉消息 */
    private Long lastMsgId;
    private Long peerId;
    private String peerName;
    private String peerAvatar;
    private Integer peerCreditScore;
    /** 1=商家（私聊列表里显示"商家"徽章） */
    private Integer peerIdentityFlag;
    private Boolean peerOnline;

    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private BigDecimal itemPrice;
    private Integer itemStatus;
    private String itemStatusText;

    /** 我在本会话里的角色：buyer / seller（来源商品已删除时为 null）——聊天气泡与列表用它区分双方 */
    private String myRole;
    private String peerRole;

    /** 我是否拉黑了对方 */
    private Boolean blockedByMe;
    /** 对方是否拉黑了我 */
    private Boolean blockedByPeer;
    /** 当前是否允许发送（任一方拉黑则不可发） */
    private Boolean canSend;

    private String lastMsgText;
    private LocalDateTime lastMsgAt;
    private Integer unread;
    private Integer riskLevel;
    private LocalDateTime createdAt;
}
