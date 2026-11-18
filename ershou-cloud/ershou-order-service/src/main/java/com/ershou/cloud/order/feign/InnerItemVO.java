package com.ershou.cloud.order.feign;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * item-service 内部接口返回的商品数据（契约与 item-member 对齐，**只加字段不改名**）。
 *
 * <p>{@code GET /inner/items/{id}} / {@code GET /inner/items/batch} 返回 {@code Result<InnerItemVO>}。
 * item-service 处于迁移中时可能只返回前 7 个字段，缺的字段为 null —— 调用方按"降级可用"处理
 * （例如 preferPointId 为 null 时购物车合并面交的面交点留空 = 待协商）。
 */
@Data
public class InnerItemVO {

    private Long id;

    private String title;

    private String description;

    private String coverUrl;

    /**
     * 图片数组：item-service 内部接口里是 **JSON 字符串**（如 {@code "[\"http://...\"]"}），不是 JSON 数组。
     * 这里保持 String 原样接收（避免 Feign 反序列化失败），需要数组时由调用方用 ObjectMapper 解析。
     */
    private String images;

    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer negotiable;

    private Integer conditionLevel;

    private Long categoryId;

    private Long campusId;

    /** 商品关联的面交点（购物车合并面交/下单默认点位用） */
    private Long preferPointId;

    private Integer sellerType;

    /** 库存（锁定前的值） */
    private Integer stock;

    /** 商品状态：2=在售、3=已锁定、4=已售出… */
    private Integer status;

    private Integer isUnique;

    private Integer viewCount;

    private Integer favCount;

    private LocalDateTime publishedAt;

    private LocalDateTime expireAt;

    private Long sellerId;
}
