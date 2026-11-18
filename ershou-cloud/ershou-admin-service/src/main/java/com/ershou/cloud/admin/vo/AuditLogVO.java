package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 操作审计日志视图（字段与旧单体 AuditLogVO 完全一致） */
@Data
public class AuditLogVO {
    private Long id;
    private Long operatorId;
    private String operatorNickname;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    /** before/after 库里是 JSON 字符串，返回前端时还原为对象 */
    private Object beforeValue;
    private Object afterValue;
    private String ip;
    private LocalDateTime createdAt;
}
