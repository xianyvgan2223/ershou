package com.ershou.campus.module.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀场次里的商品 VO（设计 3.2，字段与 Web `api/platform.ts#FlashSaleItemVO` 对齐）。
 */
@Data
public class FlashSaleItemVO {
    private Long itemId;
    private String title;
    private String coverUrl;
    /** 原价（平台商品价，划线价） */
    private BigDecimal originPrice;
    private BigDecimal seckillPrice;
    /** 秒杀总库存 */
    private Integer stock;
    /** 剩余库存（实时读 Redis） */
    private Integer leftStock;
    private Integer soldCount;
    private Integer perUserLimit;
    /** 当前用户是否已抢（未登录恒 false） */
    private Boolean grabbed = false;
}
