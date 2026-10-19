package com.ershou.cloud.item.vo;

import com.ershou.cloud.item.entity.Item;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 内部商品视图 —— order-service 通过 OpenFeign {@code GET /inner/items/{id}} 获取（设计 4.3）。
 *
 * <p>字段名即跨服务契约字段名（order-member 已确认）：id/title/coverUrl/price/stock/status/sellerId。
 * 加字段只能追加、不能改名。状态不做过滤，由调用方判断 {@code status=2}。
 */
@Data
public class InnerItemVO {

    private Long id;
    private String title;
    private String coverUrl;
    private BigDecimal price;
    private Integer stock;
    private Integer status;
    private Long sellerId;

    public static InnerItemVO from(Item item) {
        InnerItemVO vo = new InnerItemVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setCoverUrl(item.getCoverUrl());
        vo.setPrice(item.getPrice());
        vo.setStock(item.getStock());
        vo.setStatus(item.getStatus());
        vo.setSellerId(item.getSellerId());
        return vo;
    }
}
