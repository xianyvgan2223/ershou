package com.ershou.cloud.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面交单返回结构（设计 4.4：返回 {@code Result<OrderVO>}，含 orderId/orderNo/itemId/商品信息/status）。
 *
 * <p>字段与单体 {@code OrderVO} 的核心子集对齐，前端「我的面交」页面可直接复用。
 */
@Data
public class OrderVO {

    /** 订单 id（JSON 里同时给 id 与 orderId，兼容前端两种叫法） */
    private Long id;

    private Long orderId;

    private String orderNo;

    private Long itemId;

    /** 商品标题：优先用 Feign 实时数据（演示服务间拼装），Feign 不可用时用下单快照 */
    private String itemTitle;

    /** 商品封面：同上 */
    private String itemCover;

    private Long buyerId;

    private Long sellerId;

    private Integer quantity;

    private BigDecimal dealPrice;

    /** 2=待面交（本切片创建即 2；1 待卖家确认在单体流程里） */
    private Integer status;

    private String statusText;

    private Long pointId;

    private String remark;

    private LocalDateTime createdAt;
}
