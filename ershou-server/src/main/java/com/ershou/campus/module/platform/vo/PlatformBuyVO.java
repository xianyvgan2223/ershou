package com.ershou.campus.module.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台下单结果（设计 3.3，字段与 Web `api/platform.ts#PlatformBuyVO` 对齐）。
 * 数据来自 {@code OrderService#platformBuy} 的返回 Map。
 */
@Data
public class PlatformBuyVO {
    private Long orderId;
    private String orderNo;
    private Long itemId;
    private BigDecimal dealPrice;
    private Long pointId;
    private String itemTitle;
    private String itemCover;
}
