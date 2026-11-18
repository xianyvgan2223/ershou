package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台订单概览（order-service /inner/orders/platform/summary 契约）。
 *
 * <p>order-service 实际返回 {@code {total,gmv,pendingPickup,completed,campusIdIgnored}}，
 * 这里同时兼容 gmv / gmvTotal 两种字段名（历史单体口径叫 gmvTotal）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformOrderSummaryVO {

    /** 平台订单总数 */
    private Long total = 0L;
    /** 今日新增（order-service 当前未返回，缺省 0） */
    private Long orderToday = 0L;
    /** 待自提（status=2） */
    private Long pendingPickup = 0L;
    /** 待确认（status=1；平台单一般没有，字段保留对齐旧单体） */
    private Long waitingConfirm = 0L;
    /** 已完成（status=3） */
    private Long completed = 0L;
    /** 累计成交额（新字段名） */
    private BigDecimal gmv;
    /** 累计成交额（历史字段名） */
    private BigDecimal gmvTotal;
    /** 今日成交额（order-service 当前未返回，缺省 0） */
    private BigDecimal gmvToday = BigDecimal.ZERO;

    /** 归一化累计 GMV：优先 gmv，其次 gmvTotal */
    public BigDecimal resolveGmv() {
        if (gmv != null) {
            return gmv;
        }
        return gmvTotal == null ? BigDecimal.ZERO : gmvTotal;
    }
}
