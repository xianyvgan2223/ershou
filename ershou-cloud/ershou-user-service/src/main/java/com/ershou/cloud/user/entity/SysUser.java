package com.ershou.cloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表（`ershou_user.sys_user`）。
 *
 * <p>字段与单体 `com.ershou.campus.module.user.entity.SysUser` **完全一致**（同一份建表 SQL，
 * 数据由 migrate-data.ps1 从旧库快照迁移），因此 Web / App 拿到的 UserVO 不会变。
 */
@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String phone;
    private String phoneHash;
    private String nickname;
    private String avatar;
    private Long schoolId;
    private Long campusId;
    private Integer roleFlags;
    private Integer identityFlag;
    private String shopName;
    private String shopIntro;
    private Integer licenseVerified;
    private Integer creditScore;
    private Integer creditLevel;
    private Integer riskScore;
    private String riskTags;
    private String deviceId;
    private Integer deviceAccountCount;
    private Integer publishLimit;
    private Integer chatLimit;
    private Integer status;
    private LocalDateTime banUntil;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
