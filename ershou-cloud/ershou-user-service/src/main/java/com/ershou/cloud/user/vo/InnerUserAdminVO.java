package com.ershou.cloud.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端用户列表行（`GET /inner/users`）。
 *
 * <p>字段与单体运营后台 `AdminUserVO` 对齐（admin-service 的"用户管理"页可直接渲染），
 * 差异只有两处：
 * <ul>
 *   <li>`phone` 是**脱敏号**（与单体一致：AES 解密后 `138****1234`），内部接口不下发明文；</li>
 *   <li>追加 `schoolName/campusName` 便于列表直接展示学校校区（单体后台页面是按 id 再查的）。</li>
 * </ul>
 */
@Data
public class InnerUserAdminVO {

    private Long id;
    private String nickname;
    private String avatar;
    /** 脱敏手机号 */
    private String phone;
    private Long schoolId;
    private String schoolName;
    private Long campusId;
    private String campusName;
    private String shopName;
    private Integer roleFlags;
    private Integer identityFlag;
    private Integer creditScore;
    private Integer creditLevel;
    /** 信用等级文案（1=优秀 2=良好 3=一般 4=风险，与单体 AdminViewAssembler 一致） */
    private String creditLevelText;
    private Integer riskScore;
    private Integer status;
    /** 状态文案（1=正常 2=限权 3=封禁） */
    private String statusText;
    private LocalDateTime banUntil;
    private Integer publishLimit;
    private Integer chatLimit;
    private String deviceId;
    private Integer deviceAccountCount;
    private LocalDateTime lastLoginAt;
    /** 加入时间 */
    private LocalDateTime createdAt;
}
