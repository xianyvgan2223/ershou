package com.ershou.campus.module.item.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ItemVO {
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
    private String categoryName;
    private Long campusId;
    private Long preferPointId;
    private String preferPointName;
    private String preferPointGuide;
    private Integer status;
    private String statusText;
    private Integer stock;
    private Integer isUnique;
    private Integer viewCount;
    private Integer favCount;
    private LocalDateTime publishedAt;
    private SellerVO seller;
    /** 搜索命中时的标题高亮片段（只含 <em> 标签，ES 已做 HTML 转义） */
    private String highlightTitle;
    /** 搜索命中时的描述高亮片段 */
    private String highlightDescription;
}
