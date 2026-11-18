package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台代卖 / 自提点 VO（设计 3.2 / 3.4，字段与 Web `api/platform.ts#PlatformPointVO` 对齐）。
 */
@Data
public class PlatformPointVO {
    private Long id;
    private Long campusId;
    /** 校区名（后端补齐，后台表格直接显示，前端不用再查字典） */
    private String campusName;
    private String name;
    private String address;
    private BigDecimal lng;
    private BigDecimal lat;
    private String openHours;
    private String contactPhone;
    private String guideText;
    private String coverUrl;
    private Integer sort;
    /** 1=启用 0=停用（软删也是 0） */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
