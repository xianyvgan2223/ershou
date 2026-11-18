package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 平台商品计数概览（item-service /inner/items/platform/summary 契约）。
 *
 * <p>item-service(T102) 实际返回：{@code {campusId,platformItemCount,onSaleCount,totalStock,soldCount,gmv,note}}；
 * 这里同时兼容早期约定的 onSale/total/lowStock 字段名，并提供归一化取值方法。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformItemSummaryVO {

    // ---- item-service 实际字段 ----
    private Long platformItemCount;
    private Long onSaleCount;
    private Long totalStock;
    private Long soldCount;

    // ---- 早期/扩展字段（低库存告警，item-service 可后续补 lowStockCount） ----
    private Long lowStockCount;
    private Long onSale;
    private Long total;
    private Long lowStock;

    /** 在售平台商品数 */
    public long resolveOnSale() {
        if (onSale != null) {
            return onSale;
        }
        return onSaleCount == null ? 0L : onSaleCount;
    }

    /** 平台商品总数（含全部状态） */
    public long resolveTotal() {
        if (total != null) {
            return total;
        }
        return platformItemCount == null ? 0L : platformItemCount;
    }

    /** 低库存件数（item-service 暂未提供时按 0 展示，不阻塞运营台其它数字） */
    public long resolveLowStock() {
        if (lowStock != null) {
            return lowStock;
        }
        return lowStockCount == null ? 0L : lowStockCount;
    }
}
