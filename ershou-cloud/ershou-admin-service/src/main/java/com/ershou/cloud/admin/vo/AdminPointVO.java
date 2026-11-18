package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 运营后台的面交点视图（字段与旧单体 AdminPointVO 完全一致；数据来自 map-service /inner/points） */
@Data
public class AdminPointVO {
    private Long id;
    private Long campusId;
    private String campusName;
    private String name;
    private Integer source;
    private Integer pointType;
    private BigDecimal coordX;
    private BigDecimal coordY;
    private BigDecimal lng;
    private BigDecimal lat;
    private Integer safetyScore;
    private List<String> safeTags;
    private String guideText;
    private Integer status;
    private Integer auditStatus;
    private Long creatorId;
    private String creatorNickname;
    private LocalDateTime createdAt;
}
