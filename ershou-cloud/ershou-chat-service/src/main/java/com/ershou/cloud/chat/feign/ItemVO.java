package com.ershou.cloud.chat.feign;

import lombok.Data;

import java.math.BigDecimal;

/**
 * item-service 内部商品视图（契约：/inner/items/{id}，与 order-service 的 InnerItemVO 字段一致）。
 * 会话里只需要商品卡片展示字段。
 */
@Data
public class ItemVO {

    private Long id;
    private String title;
    private String coverUrl;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private Long sellerId;
}
