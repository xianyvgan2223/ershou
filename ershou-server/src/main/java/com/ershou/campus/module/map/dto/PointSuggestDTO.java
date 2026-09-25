package com.ershou.campus.module.map.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PointSuggestDTO {
    @NotNull(message = "缺少校区")
    private Long campusId;

    @NotBlank(message = "请填写点位名称")
    private String name;

    @NotNull(message = "请在地图上选点")
    private BigDecimal coordX;

    @NotNull(message = "请在地图上选点")
    private BigDecimal coordY;

    private String guideText;
    private Integer pointType = 9;
}
