package com.ershou.cloud.map.feign;

import lombok.Data;

import java.math.BigDecimal;

/**
 * item-service {@code GET /inner/items/count-by-point?campusId=} 的单行结果。
 *
 * <p>契约最小字段：{@code {pointId,count}}。**当前 item-service 实现口径：count = 该点位"在售(status=2)"商品数**
 * （见 item-service {@code ItemInnerService#countByPoint}），不含审核中/锁定/草稿。
 * 因此本服务把 count 同时作为 itemCount 与 onSaleCount 的降级值；若 item-service 以后补齐
 * {@code itemCount（还有戏）/onSaleCount/minPrice} 增强字段，本类会自动优先使用（字段已就绪）。
 *
 * <p>与单体差异（遗留）：单体 itemCount 含"还有戏"status 0/1/2/3，地图气泡在"只有审核中商品"时也显示点位；
 * 拆分后 onlyWithItems 过滤按在售口径 → 这类点位暂不显示（见报告"已知差异"）。
 */
@Data
public class PointItemStatVO {

    private Long pointId;

    /** 契约字段：该点位商品数（"还有戏"口径） */
    private Integer count;

    /** 可选：与 count 同义（若 item-service 用 itemCount 命名） */
    private Integer itemCount;

    /** 可选：买家可见在售数（status=2） */
    private Integer onSaleCount;

    /** 可选：该点位上"还有戏"商品的最低价 */
    private BigDecimal minPrice;

    /** 统一取"还有戏"商品数：优先 itemCount，其次契约 count */
    public int resolveItemCount() {
        if (itemCount != null && itemCount >= 0) {
            return itemCount;
        }
        if (count != null && count >= 0) {
            return count;
        }
        return 0;
    }

    /** 买家可见在售数：未下发时退化用 count（地图气泡与点开列表可能差审核中商品，属降级口径） */
    public int resolveOnSaleCount() {
        if (onSaleCount != null && onSaleCount >= 0) {
            return onSaleCount;
        }
        return resolveItemCount();
    }
}
