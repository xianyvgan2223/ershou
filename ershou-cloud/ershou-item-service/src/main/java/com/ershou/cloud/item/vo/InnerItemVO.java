package com.ershou.cloud.item.vo;

import com.ershou.cloud.item.entity.Item;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 内部商品视图 —— 服务间 OpenFeign 契约（T102 契约 §三 item-service）。
 *
 * <p>字段只增不改（order/platform/admin 各服务依赖）：
 * <ul>
 *   <li>第 5 轮基础：id/title/coverUrl/price/stock/status/sellerId</li>
 *   <li>T102（order-member）：preferPointId/campusId/isUnique/originalPrice/images/publishedAt</li>
 *   <li>T102（platform-service）：sellerType/platformPointId</li>
 *   <li>T102（admin-service 管理分页，Lead 最终补充）：
 *       description/categoryId/categoryName/campusName/preferPointName/auditReason/rejectCode/
 *       viewCount/favCount/expireAt/createdAt</li>
 * </ul>
 * {@link #from(Item)} 只做 DB 字段直填；分类名/点位名/校区名由 Service 层按需批量补齐（含降级）。
 */
@Data
public class InnerItemVO {

    private Long id;
    private String title;
    private String description;
    private String coverUrl;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private Integer status;
    private Long sellerId;
    // T102 追加（order/platform）
    private Long preferPointId;
    private String preferPointName;
    private Long campusId;
    private String campusName;
    private Integer isUnique;
    private String images;
    private List<String> imageList;
    private LocalDateTime publishedAt;
    private Integer sellerType;
    private Long platformPointId;
    // T102 追加（admin 管理分页）
    private Long categoryId;
    private String categoryName;
    private String auditReason;
    private String rejectCode;
    private Integer viewCount;
    private Integer favCount;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;

    public static InnerItemVO from(Item item) {
        if (item == null) {
            return null;
        }
        InnerItemVO vo = new InnerItemVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setDescription(item.getDescription());
        vo.setCoverUrl(item.getCoverUrl());
        vo.setPrice(item.getPrice());
        vo.setOriginalPrice(item.getOriginalPrice());
        vo.setStock(item.getStock());
        vo.setStatus(item.getStatus());
        vo.setSellerId(item.getSellerId());
        vo.setPreferPointId(item.getPreferPointId());
        vo.setCampusId(item.getCampusId());
        vo.setIsUnique(item.getIsUnique());
        vo.setImages(item.getImages());
        vo.setImageList(parseImages(item.getImages()));
        vo.setPublishedAt(item.getPublishedAt());
        vo.setSellerType(item.getSellerType());
        vo.setPlatformPointId(item.getPlatformPointId());
        vo.setCategoryId(item.getCategoryId());
        vo.setAuditReason(item.getAuditReason());
        vo.setRejectCode(item.getRejectCode());
        vo.setViewCount(item.getViewCount());
        vo.setFavCount(item.getFavCount());
        vo.setExpireAt(item.getExpireAt());
        vo.setCreatedAt(item.getCreatedAt());
        return vo;
    }

    /** images JSON 数组串 → List（解析失败返回空数组，不影响其他字段） */
    private static List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return Arrays.asList(raw);
        }
    }
}
