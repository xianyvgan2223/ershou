package com.ershou.cloud.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 运营操作审计日志（ershou_admin.sys_audit_log，结构同旧单体） */
@Data
@TableName("sys_audit_log")
public class SysAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 操作人（网关注入的 X-User-Id） */
    private Long operatorId;
    /** 模块：item/user/point/review/platform/report/ai… */
    private String module;
    /** 动作：approve/reject/batch-approve/force-offline/update-status/adjust-credit/report-handle… */
    private String action;
    private String targetType;
    private Long targetId;
    /** 变更前（JSON 字符串） */
    private String beforeValue;
    /** 变更后（JSON 字符串） */
    private String afterValue;
    private String ip;
    private LocalDateTime createdAt;
}
