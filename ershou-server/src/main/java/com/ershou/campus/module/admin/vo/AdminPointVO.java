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
    private BigDecimal coordX;
    private BigDecimal coordY;
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
