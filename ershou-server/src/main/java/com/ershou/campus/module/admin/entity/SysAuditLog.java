package com.ershou.campus.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 运营操作审计日志（谁在何时改了什么） */
@Data
@TableName("sys_audit_log")
public class SysAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String beforeValue;
    private String afterValue;
    private String ip;
    private LocalDateTime createdAt;
}
