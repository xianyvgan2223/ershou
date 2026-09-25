package com.ershou.campus.module.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 运营操作审计日志视图 */
@Data
public class AuditLogVO {
    private Long id;
    private Long operatorId;
    private String operatorNickname;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private Object beforeValue;
    private Object afterValue;
    private String ip;
    private LocalDateTime createdAt;
}
