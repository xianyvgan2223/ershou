package com.ershou.cloud.item.vo;

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
    /** 卖家类型：1=个人卖家，2=平台自营（官方代卖）。前端据此渲染「官方代卖」角标 */
    private Integer sellerType;
    /** 平台代卖/自提点 id（仅平台商品有值，个人商品为 null） */
    private Long platformPointId;
    /**
     * 平台代卖/自提点名称（仅平台商品有值）。
     *
     * <p>TODO(platform): 点位数据属 platform 模块（platform_consign_point 表），item 模块不反向依赖它，
     * 所以这里暂时恒为 null；前端用 {@code platformPointId} 调 platform 接口解析名称。
     * 若后续 Lead 决定允许 item → platform 的只读依赖，再在 ItemService 组装时按 id 批量取名。
     */
    private String platformPointName;
    private Integer status;
    private String statusText;
    private Integer stock;
    private Integer isUnique;
    private Integer viewCount;
    private Integer favCount;
    /** 当前登录用户是否已收藏（未登录恒为 false；列表 / 详情接口都会返回） */
    private Boolean favorited = false;
    private LocalDateTime publishedAt;
    /** 有效期截止时间：前端据此显示「还剩 X 天自动下架」 */
    private LocalDateTime expireAt;
    private SellerVO seller;
    /** 搜索命中时的标题高亮片段（只含 <em> 标签，ES 已做 HTML 转义） */
    private String highlightTitle;
    /** 搜索命中时的描述高亮片段 */
    private String highlightDescription;
}
