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
    /**
     * 卖家类型：1=个人卖家，2=平台自营（官方代卖）。
     *
     * <p>只用于索引里保留平台标注，公开检索不按它过滤（首页/搜索都要能看到平台商品）。
     * 老索引没有这个 mapping 时 ES 会按动态 mapping 收下（数值 → long），不影响写入与检索；
     * 重建索引后按显式 mapping 的 integer 落。
     */
    private Integer sellerType;
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
        d.setSellerType(item.getSellerType());
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
