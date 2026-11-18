package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 运营后台的用户视图（字段与旧单体 AdminUserVO 完全一致） */
@Data
public class AdminUserVO {
    private Long id;
    private String nickname;
    private String avatar;
    /** 脱敏手机号（user-service 已按 AES 解密后 mask） */
    private String phone;
    private Long schoolId;
    private Long campusId;
    private String shopName;
    private Integer roleFlags;
    private Integer identityFlag;
    private Integer creditScore;
    private Integer creditLevel;
    private String creditLevelText;
    private Integer riskScore;
    private Integer status;
    private String statusText;
    private LocalDateTime banUntil;
    private Integer publishLimit;
    private Integer chatLimit;
    private String deviceId;
    private Integer deviceAccountCount;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
