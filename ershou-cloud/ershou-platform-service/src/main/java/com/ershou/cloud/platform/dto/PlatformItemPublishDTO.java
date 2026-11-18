package com.ershou.cloud.platform.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 后台发布平台商品的入参（设计 3.4）。
 *
 * <p>字段与 Web `api/platform.ts#PlatformItemPublishParams` 对齐（web-platform-member 已按此实现）。
 * 平台商品免审核直接 status=2、seller_id=平台账号、seller_type=2、有效期 +30 天，均由后端补。
 */
@Data
public class PlatformItemPublishDTO {

    @NotBlank(message = "商品标题不能为空")
    @Size(max = 80, message = "商品标题最多 80 字")
    private String title;

    private String description;

    @NotNull(message = "商品分类不能为空")
    private Long categoryId;

    @NotNull(message = "成色不能为空")
    @Min(value = 1, message = "成色只能是 1~5")
    @Max(value = 5, message = "成色只能是 1~5")
    private Integer conditionLevel;

    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格必须大于 0")
    private BigDecimal price;

    /** 原价（划线价），不传时回退成 price */
    private BigDecimal originalPrice;

    /** 库存，默认 1 */
    @Min(value = 1, message = "库存至少 1")
    private Integer stock;

    @NotEmpty(message = "至少上传一张商品图片")
    private List<String> images;

    /** 代卖点 id：必填，且必须属于 campusId 对应校区 */
    @NotNull(message = "代卖点不能为空")
    private Long platformPointId;

    /** 校区 id；不传时取代卖点所属校区 */
    private Long campusId;
}
