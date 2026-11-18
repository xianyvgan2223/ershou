package com.ershou.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 购物车条目（第 103 轮从单体 {@code module.cart.entity.CartItem} 迁入 order-service）。
 *
 * <p>库：{@code ershou_order}（交易域自有库）；表结构由 {@code sql/split-order.sql} 从旧单体复制。
 * {@code sellerId} 是冗余列：按卖家分组做「合并面交」时不必再回查 item。
 */
@Data
@TableName("cart_item")
public class CartItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long itemId;

    /** 冗余卖家 id：按卖家分组用 */
    private Long sellerId;

    private Integer quantity;

    /** 规格/成色备注（历史字段，本轮前端不用） */
    private String specText;

    /** 选中态（历史字段：旧前端本地勾选，后端只写不读，保留列兼容） */
    private Integer selected;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
