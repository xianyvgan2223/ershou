package com.ershou.campus.module.search.document;

import com.ershou.campus.module.item.entity.Item;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * ES 商品文档。
 * 刻意只用 String / 数值 / Long 时间戳等简单类型：写入与检索都走 JSON，
 * 避免 LocalDateTime 在不同 Jackson 模块下序列化形态不一致。
 */
@Data
public class ItemDocument {

    private Long id;
    private Long sellerId;
    private Long schoolId;
    private Long campusId;
    private Long categoryId;
    private String categoryName;
    private String title;
    private String description;
    private Double price;
    private Double originalPrice;
    private Integer conditionLevel;
    private Integer negotiable;
    private Integer stock;
    private Integer isUnique;
    private String coverUrl;
    private Integer status;
    /** epoch millis，排序用 */
    private Long publishedAt;
    private Long updatedAt;
    private Integer viewCount;
    private Integer favCount;

    public static ItemDocument from(Item item, String categoryName) {
        ItemDocument d = new ItemDocument();
        d.setId(item.getId());
        d.setSellerId(item.getSellerId());
        d.setSchoolId(item.getSchoolId());
        d.setCampusId(item.getCampusId());
        d.setCategoryId(item.getCategoryId());
        d.setCategoryName(categoryName);
        d.setTitle(item.getTitle());
        d.setDescription(item.getDescription());
        d.setPrice(item.getPrice() == null ? null : item.getPrice().doubleValue());
        d.setOriginalPrice(item.getOriginalPrice() == null ? null : item.getOriginalPrice().doubleValue());
        d.setConditionLevel(item.getConditionLevel());
        d.setNegotiable(item.getNegotiable());
        d.setStock(item.getStock());
        d.setIsUnique(item.getIsUnique());
        d.setCoverUrl(item.getCoverUrl());
        d.setStatus(item.getStatus());
        d.setPublishedAt(epoch(item.getPublishedAt() != null ? item.getPublishedAt() : item.getCreatedAt()));
        d.setUpdatedAt(epoch(item.getUpdatedAt()));
        d.setViewCount(item.getViewCount());
        d.setFavCount(item.getFavCount());
        return d;
    }

    private static Long epoch(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
