package com.ershou.campus.module.cart.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

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
        private Integer status;
        private String statusText;
        private Long preferPointId;
        private String preferPointName;
    }
}
