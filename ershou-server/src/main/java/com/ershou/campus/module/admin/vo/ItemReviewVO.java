package com.ershou.campus.module.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上架审核页的列表项。
 *
 * <p>{@code title} 一律取<b>商品当前</b>的标题（实时），{@code titleSnapshot} 才是入队那一刻的快照——
 * 卖家在待审期间又改了名，审核员看到的必须是最新那版，否则等于在审旧内容。
 */
@Data
public class ItemReviewVO {

    /** 审核记录 id */
    private Long id;
    /** 商品 id */
    private Long itemId;

    private String title;
    /** 入队时的标题快照（改名后与 title 不同，前端用来看"改成了什么"） */
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

    private java.math.BigDecimal price;

    /** 商品当前状态（2 在售 / 3 已锁定 / 4 已售出 / 1 审核中 …） */
    private Integer itemStatus;
    private String itemStatusText;
    /** 商品是否还在"能被审核员处置"的范围内（在售/锁定/审核中）；false 时前端把操作按钮置灰 */
    private Boolean actionable;

    /** 1=新上架 2=改名/改描述 3=到期重新上架 4=违规后二次申请 5=存量导入 */
    private Integer reviewType;
    private String reviewTypeText;
    private Integer revision;

    /** 0=待审核 1=已审核 */
    private Integer status;
    private String statusText;
    /** 1=已审核 2=已下架 */
    private Integer reviewAction;
    private String reviewActionText;

    private LocalDateTime queuedAt;
    private LocalDateTime firstQueuedAt;
    private LocalDateTime contentChangedAt;
    private LocalDateTime reviewedAt;
    private String note;

    /**
     * 处置人（已审核 / 已下架时是谁点的）。
     *
     * <p>待审状态下这个字段<b>恒为空</b>：没有做"认领 / 抢单"机制 ——
     * 一个审核页几十条、审核员点一下就归档，加锁只会让"我点了却提示被别人锁了"这种
     * 无意义摩擦变多。并发由条件更新兜底：谁先点到谁生效，另一个收到
     * {@code 61001} 并看到一行轻提示。
     */
    private Long reviewerId;
    private String reviewerNickname;
    private LocalDateTime claimedAt;

    /** 内容改过（在售期间改名/改描述）—— 前端打「已改名」标记 */
    private Boolean contentChanged;
    /** 该商品历史上被违规下架过（第二列及以后的轮次）—— 前端打「曾违规」标记 */
    private Boolean violationBefore;
    private Integer reviewRound;
    /** 历史被驳回次数，供审核员参考卖家的历史 */
    private Integer rejectCount;

    private LocalDateTime publishedAt;
    private LocalDateTime expireAt;
}
