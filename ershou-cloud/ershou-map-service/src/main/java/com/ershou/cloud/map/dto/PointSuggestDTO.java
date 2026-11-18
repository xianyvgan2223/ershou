package com.ershou.cloud.map.dto;

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

    /** 自绘底图用的像素坐标（map_type=1），与 lng/lat 二选一 */
    private BigDecimal coordX;

    private BigDecimal coordY;

    /** 高德地图坐标（GCJ-02 火星坐标，map_type=2 用这个） */
    private BigDecimal lng;

    private BigDecimal lat;

    private String guideText;
    private Integer pointType = 9;
}
