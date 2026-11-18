package com.ershou.cloud.platform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 运营台 · 场次添加秒杀商品入参（设计 3.4 `POST /flash-sales/{id}/items`）。
 * 字段与 Web `api/platform-console.ts#ConsoleFlashItemSaveParams` 对齐。
 */
@Data
public class FlashSaleItemSaveDTO {

    @NotNull(message = "商品不能为空")
    private Long itemId;

    @NotNull(message = "秒杀价不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价必须大于 0")
    private BigDecimal seckillPrice;

    @NotNull(message = "秒杀库存不能为空")
    @Min(value = 1, message = "秒杀库存至少 1")
    private Integer seckillStock;

    /** 每人限购，默认 1 */
    @Min(value = 1, message = "每人限购至少 1")
    private Integer perUserLimit;

    private Integer sort;
}
