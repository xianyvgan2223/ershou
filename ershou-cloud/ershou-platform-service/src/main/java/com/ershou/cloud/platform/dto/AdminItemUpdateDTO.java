package com.ershou.cloud.platform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 运营台 · 编辑平台商品入参（设计 3.2 `PUT /items/{id}`，只允许改标题/价格/库存/代卖点）。
 * 所有字段可空 = 不修改；字段与 Web `api/platform-console.ts#ConsoleItemUpdateParams` 对齐。
 */
@Data
public class AdminItemUpdateDTO {

    @Size(max = 80, message = "标题最多 80 字")
    private String title;

    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    /** 库存 >=0（设为 0 = 暂时卖不了，但仍在售列表里可再改回来） */
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    /** 代卖点 id；传了就必须存在且与商品同校区 */
    private Long platformPointId;
}
