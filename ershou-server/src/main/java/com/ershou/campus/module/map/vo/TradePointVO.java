package com.ershou.campus.module.map.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class TradePointVO {
    private Long id;
    private String name;
    private Integer type;
    private BigDecimal coordX;
    private BigDecimal coordY;
    private BigDecimal lng;
    private BigDecimal lat;
    private Integer safetyScore;
    private List<String> safeTags;
    private List<String> availablePeriod;
    private String guideText;
    /** 该点位在售商品数（地图气泡） */
    private Integer itemCount;
    private BigDecimal minPrice;
}
