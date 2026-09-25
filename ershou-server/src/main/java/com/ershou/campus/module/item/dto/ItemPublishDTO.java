package com.ershou.campus.module.item.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ItemPublishDTO {

    @NotBlank(message = "请填写标题")
    @Size(max = 80, message = "标题不能超过 80 字")
    private String title;

    @Size(max = 2000, message = "描述不能超过 2000 字")
    private String description;

    @NotNull(message = "请选择分类")
    private Long categoryId;

    @NotNull(message = "请选择成色")
    @Min(value = 1, message = "成色取值 1-5")
    @Max(value = 5, message = "成色取值 1-5")
    private Integer conditionLevel;

    @NotNull(message = "请填写价格")
    @DecimalMin(value = "0.01", message = "价格至少 0.01 元")
    @DecimalMax(value = "99999.99", message = "价格过高")
    private BigDecimal price;

    private BigDecimal originalPrice;

    private Integer negotiable = 1;

    private Integer stock = 1;

    private Integer isUnique = 1;

    @NotEmpty(message = "至少上传 1 张图片")
    @Size(max = 9, message = "最多 9 张图片")
    private List<String> images;

    private Long preferPointId;

    private String videoUrl;
}
