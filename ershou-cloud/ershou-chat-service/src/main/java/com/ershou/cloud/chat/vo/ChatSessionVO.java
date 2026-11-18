package com.ershou.cloud.chat.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 会话列表项：对方信息 + 来源商品卡片 + 最后一条消息 + 未读数 */
@Data
public class ChatSessionVO {

    private Long id;
    private Long lastMsgId;
    private Long peerId;
    private String peerName;
    private String peerAvatar;
    private Integer peerCreditScore;
    private Integer peerIdentityFlag;
    private Boolean peerOnline;

    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private BigDecimal itemPrice;
    private Integer itemStatus;
    private String itemStatusText;

    private String myRole;
    private String peerRole;

    private Boolean blockedByMe;
    private Boolean blockedByPeer;
    private Boolean canSend;

    private String lastMsgText;
    private LocalDateTime lastMsgAt;
    private Integer unread;
    private Integer riskLevel;
    private LocalDateTime createdAt;
}
