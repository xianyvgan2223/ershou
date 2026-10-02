package com.ershou.campus.module.item.dto;

import lombok.Data;

@Data
public class ItemQueryDTO {
    private long pageNum = 1;
    private long pageSize = 20;
    private String keyword;
    private Long categoryId;
    private Long campusId;
    private Long sellerId;
    /**
     * 按面交点精确筛选（地图上点一个交易点 → 看这个点位上在售的商品）。
     *
     * <p>以前移动端的"点位商品"是**拉 50 条再按 preferPointName 过滤**：
     * 既浪费，又不可靠（同名点位会串在一起、超过 50 件就漏）。
     * 有了精确 id 过滤，地图上的商品小窗一次只取几条。
     */
    private Long preferPointId;
    /** latest | price_asc | price_desc */
    private String sort = "latest";
}
