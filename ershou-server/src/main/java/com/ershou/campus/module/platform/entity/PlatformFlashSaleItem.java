package com.ershou.campus.module.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀场次里的商品（设计 2.2）。
 *
 * <p>{@code sold_count} 只是展示用的 DB 计数；<b>库存真值以 Redis 为准</b>，
 * 预热时用 {@code seckill_stock - COUNT(platform_seckill_record)} 重算（设计第四节）。
 */
@Data
@TableName("platform_flash_sale_item")
public class PlatformFlashSaleItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long flashSaleId;
    private Long itemId;
    private BigDecimal seckillPrice;
    private Integer seckillStock;
    private Integer soldCount;
    private Integer perUserLimit;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
