package com.ershou.cloud.item.dto;

import lombok.Data;

import java.util.List;

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

    /**
     * 一次查**一组**点位（地图上的合并气泡用）。
     *
     * <p>为什么要它：地图会把 100 米内的点位合并成一个"N"气泡，点开小窗应该看到
     * 组内**所有**点位的商品。只按代表点 id 查会少显示 ——
     * 气泡上写着"3 件"、小窗里只有 1 件（2026-10-11 用户报的就是这个）。
     *
     * <p>传参形式是逗号分隔：{@code preferPointIds=11,12,13}（Spring 会自动拆成 List）。
     * 组本来只有几条，这里再加个上限防止被当成"全表扫描"的口子。
     */
    private List<Long> preferPointIds;
    /** latest | price_asc | price_desc */
    private String sort = "latest";
}
