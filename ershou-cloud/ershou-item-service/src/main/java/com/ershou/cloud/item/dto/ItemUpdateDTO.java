package com.ershou.cloud.item.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 编辑已发布商品的入参。
 *
 * <p>字段**全部可选**：null 表示「不改这一项」，前端只提交用户真正改过的字段。
 * 这样「只改价格」这种最常见的场景不需要把标题、图片一起回传，避免误覆盖。
 */
@Data
public class ItemUpdateDTO {

    @Size(max = 80, message = "标题不能超过 80 字")
    private String title;

    @Size(max = 2000, message = "描述不能超过 2000 字")
    private String description;

    private Long categoryId;

    @Min(value = 1, message = "成色取值 1-5")
    @Max(value = 5, message = "成色取值 1-5")
    private Integer conditionLevel;

    @DecimalMin(value = "0.01", message = "价格至少 0.01 元")
    @DecimalMax(value = "99999.99", message = "价格过高")
    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer negotiable;

    private Integer stock;

    private Integer isUnique;

    @Size(max = 9, message = "最多 9 张图片")
    private List<String> images;

    private Long preferPointId;

    /**
     * 平台代卖/自提点 id（T102 追加：platform-service 运营台编辑平台商品时改代卖点）。
     * 仅内部 {@code PUT /inner/items/platform/{id}} 使用；个人商品编辑忽略。
     */
    private Long platformPointId;

    private String videoUrl;
}
