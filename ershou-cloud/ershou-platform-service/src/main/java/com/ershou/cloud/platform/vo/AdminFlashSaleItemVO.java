package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 运营台 · 秒杀场次里的商品（AdminFlashSaleVO.items[]）。
 * 字段与 Web `api/platform-console.ts#ConsoleAdminFlashItemVO` 对齐。
 */
@Data
public class AdminFlashSaleItemVO {
    private Long itemId;
    private String title;
    private String coverUrl;
    /** 原价（平台商品价，划线价） */
    private BigDecimal originPrice;
    private BigDecimal seckillPrice;
    private Integer seckillStock;
    private Integer perUserLimit;
    private Integer soldCount;
    /** 实时剩余（Redis，未预热回退 seckillStock-soldCount） */
    private Integer leftStock;
    private Integer sort;
}
