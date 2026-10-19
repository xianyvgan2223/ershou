package com.ershou.cloud.order.feign;

import lombok.Data;

import java.math.BigDecimal;

/**
 * item-service 内部接口返回的商品精简数据（契约与 item-member 对齐）。
 *
 * <p>{@code GET /inner/items/{id}} 返回 {@code Result<InnerItemVO>}，data 字段：
 * {@code id/title/coverUrl/price/stock/status/sellerId}。**不做 status 过滤**，
 * status 原样返回（2=在售），由 order-service 自己判断能否下单。
 */
@Data
public class InnerItemVO {

    private Long id;

    private String title;

    private String coverUrl;

    private BigDecimal price;

    /** 库存（锁定前的值；锁定成功后 item-service 立即扣减） */
    private Integer stock;

    /** 商品状态：2=在售、3=已锁定、4=已售出… */
    private Integer status;

    private Long sellerId;
}
