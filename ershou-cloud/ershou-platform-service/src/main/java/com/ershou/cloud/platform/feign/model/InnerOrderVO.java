package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单服务内部视图（/inner/orders/** 契约；平台运营台订单列表/详情用）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InnerOrderVO {

    private Long id;
    private String orderNo;
    private Long buyerId;
    private Long sellerId;
    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private Integer quantity;
    private BigDecimal dealPrice;
    private Integer status;
    private String statusText;
    private Long pointId;
    private String pointName;
    private String remark;
    private String createdAt;
    private String buyerName;
    private String sellerName;
}
