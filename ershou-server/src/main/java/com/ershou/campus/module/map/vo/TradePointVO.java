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
    /** 审核状态：0=待审 1=通过 2=驳回；待审点位仅提交者本人可见（发布页用来标注"审核中"） */
    private Integer auditStatus;
    /** 来源：1=官方 2=用户提交 3=高德搜索落库（前端据此区分"搜出来的地名"） */
    private Integer source;
    /** 该点位在售商品数（地图气泡） */
    private Integer itemCount;
    private BigDecimal minPrice;
}
