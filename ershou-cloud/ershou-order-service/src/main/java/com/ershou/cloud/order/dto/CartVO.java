package com.ershou.cloud.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车按卖家分组 VO（与旧单体 {@code module.cart.vo.CartVO} JSON 完全一致）。
 * 前端「购物车」页按组渲染卖家 + 条目；条目字段用于勾选/合计/置灰。
 */
@Data
public class CartVO {

    private Long sellerId;

    private String sellerName;

    private List<Row> items;

    @Data
    public static class Row {

        private Long cartId;

        private Long itemId;

        private String title;

        private String coverUrl;

        private BigDecimal price;

        private Integer quantity;

        /** 商品状态：2=在售（可结算）/3=已锁定/4=已售出/5=已下架… */
        private Integer status;

        private String statusText;

        /** 卖家发布时选的面交点（前端展示 + 合并面交按它取） */
        private Long preferPointId;

        private String preferPointName;
    }
}
