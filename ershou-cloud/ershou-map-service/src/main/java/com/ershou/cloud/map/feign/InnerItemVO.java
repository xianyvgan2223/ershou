package com.ershou.cloud.map.feign;

import lombok.Data;

import java.math.BigDecimal;

/**
 * item-service {@code GET /inner/items/{id}} 的最小视图（本服务只用 {@code preferPointId}）。
 *
 * <p>用于 `GET /trade-points/recommend?itemId=`：以商品关联点位为参考坐标，把"距离分"算出来
 * （单体里是直接查 item 表，拆分后改走 Feign，禁止跨库 SQL）。
 */
@Data
public class InnerItemVO {
    private Long id;
    private String title;
    private String coverUrl;
    private BigDecimal price;
    private Integer status;
    private Integer stock;
    private Long sellerId;
    /** 商品发布时选的面交点 id（可能为 null = 待协商） */
    private Long preferPointId;
}
