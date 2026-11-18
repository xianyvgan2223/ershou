package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 平台单创建结果（order-service /inner/orders/platform 契约）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformBuyResultVO {

    private Long orderId;
    private String orderNo;
    private Long itemId;
    private Integer quantity;
    private BigDecimal dealPrice;
    private Long pointId;
    private Integer status;
    private String itemTitle;
    private String itemCover;
}
