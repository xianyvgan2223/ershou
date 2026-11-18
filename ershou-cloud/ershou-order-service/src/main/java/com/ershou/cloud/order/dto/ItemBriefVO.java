package com.ershou.cloud.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收藏列表用的商品简要 VO（字段与旧单体 {@code ItemVO} 兼容子集，前端 ItemCard 可直接渲染）。
 *
 * <p>为什么不在 order-service 复刻完整 ItemVO：收藏域只存 itemId，商品详情属 item-service；
 * 这里用 item-service 内部接口按 id 拼装「卡片够用」的字段，缺失字段为 null（前端有默认值兜底）。
 */
@Data
public class ItemBriefVO {

    private Long id;

    private String title;

    private String description;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer negotiable;

    private Integer conditionLevel;

    private String conditionText;

    private String coverUrl;

    private List<String> images;

    private Long categoryId;

    private Long campusId;

    private Long preferPointId;

    private String preferPointName;

    private String preferPointGuide;

    private Integer sellerType;

    private Integer status;

    private String statusText;

    private Integer stock;

    private Integer isUnique;

    private Integer viewCount;

    private Integer favCount;

    /** 「我的收藏」列表恒为 true */
    private Boolean favorited = true;

    private LocalDateTime publishedAt;

    private LocalDateTime expireAt;

    private SellerVO seller;
}
