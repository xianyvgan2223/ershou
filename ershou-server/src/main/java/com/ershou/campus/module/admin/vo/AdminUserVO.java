package com.ershou.campus.module.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 运营后台的用户视图 */
@Data
public class AdminUserVO {
    private Long id;
    private String nickname;
    private String avatar;
    /** 脱敏手机号 */
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
