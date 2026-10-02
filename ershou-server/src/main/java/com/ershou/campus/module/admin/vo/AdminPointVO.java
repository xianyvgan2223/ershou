package com.ershou.campus.module.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 运营后台的面交点视图 */
@Data
public class AdminPointVO {
    private Long id;
    private Long campusId;
    private String campusName;
    private String name;
    /** 1=官方 2=用户提交 */
    private Integer source;
    private Integer pointType;
    /** 自绘底图坐标（像素） */
    private BigDecimal coordX;
    private BigDecimal coordY;
    /** 第三方地图经纬度；用户提交的点位大多只有经纬度，没有像素坐标 */
    private BigDecimal lng;
    private BigDecimal lat;
    private Integer safetyScore;
    private List<String> safeTags;
    private String guideText;
    /** 1=启用 0=停用 */
    private Integer status;
    /** 0=待审 1=通过 2=驳回 */
    private Integer auditStatus;
    private Long creatorId;
    private String creatorNickname;
    private LocalDateTime createdAt;
}
