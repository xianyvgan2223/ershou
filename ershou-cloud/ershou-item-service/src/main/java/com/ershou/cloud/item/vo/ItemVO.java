package com.ershou.cloud.item.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品 VO —— 字段与单体 {@code module/item/vo/ItemVO} 对齐，Web 首页/详情可直接使用（设计 4.3）。
 *
 * <p>云切片说明：
 * <ul>
 *   <li>{@code seller} 只填 id（跨库拿不到昵称/头像，属已知限制；Web 卡片有「同学」兜底）；</li>
 *   <li>{@code preferPointName/Guide}、{@code highlight*} 暂无数据源，保持 null（Web 为可选字段）；</li>
 *   <li>时间用 {@code yyyy-MM-dd HH:mm:ss} 序列化，与单体返回格式一致（避免前端/移动端二次适配）。</li>
 * </ul>
 */
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
    /** 1=个人卖家 2=平台自营（官方代卖），Web 首页据此渲染金色角标 */
    private Integer sellerType;
    private Long platformPointId;
    private String platformPointName;
    private Integer status;
    private String statusText;
    private Integer stock;
    private Integer isUnique;
    private Integer viewCount;
    private Integer favCount;
    private Boolean favorited = false;
    /** 已售件数（第 5 轮 MQ 演示字段，Web 暂不展示） */
    private Integer saleCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    private SellerVO seller;
    private String highlightTitle;
    private String highlightDescription;
}
