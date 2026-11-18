package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 上架审核列表项（字段与旧单体 ItemReviewVO 完全一致；数据来自 item-service /inner/item-reviews） */
@Data
public class ItemReviewVO {
    private Long id;
    private Long itemId;
    private String title;
    private String titleSnapshot;
    private String description;
    private String coverUrl;
    private List<String> images;
    private Long sellerId;
    private String sellerNickname;
    private String sellerPhone;
    private Integer sellerCreditScore;
    private Long categoryId;
    private String categoryName;
    private Long campusId;
    private String campusName;
    private Long preferPointId;
    private String preferPointName;
    private BigDecimal price;
    private Integer itemStatus;
    private String itemStatusText;
    private Boolean actionable;
    private Integer reviewType;
    private String reviewTypeText;
    private Integer revision;
    private Integer status;
    private String statusText;
    private Integer reviewAction;
    private String reviewActionText;
    private LocalDateTime queuedAt;
    private LocalDateTime firstQueuedAt;
    private LocalDateTime contentChangedAt;
    private LocalDateTime reviewedAt;
    private String note;
    private Long reviewerId;
    private String reviewerNickname;
    private LocalDateTime claimedAt;
    private Boolean contentChanged;
    private Boolean violationBefore;
    private Integer reviewRound;
    private Integer rejectCount;
    private LocalDateTime publishedAt;
    private LocalDateTime expireAt;
}
