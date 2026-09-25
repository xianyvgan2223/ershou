package com.ershou.campus.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
