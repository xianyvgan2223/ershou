package com.ershou.cloud.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 当前用户视图（`GET /users/me`、`POST /auth/login` 的 user 字段）。
 *
 * <p>字段**逐个对齐单体** `com.ershou.campus.module.user.vo.UserVO`，
 * Web/App 直接复用原有解析逻辑（少一个字段前端就可能显示空）。
 */
@Data
public class UserVO {
    private Long id;
    private String nickname;
    private String avatar;
    private String phone;
    private Long schoolId;
    private String schoolName;
    private Long campusId;
    private String campusName;
    private Integer creditScore;
    private Integer creditLevel;
    private String shopName;
    /** 小铺简介（仅商家填写） */
    private String shopIntro;
    private Integer identityFlag;
    private Integer publishLimit;
    private Integer chatLimit;
    private Boolean newUser;
    /** role_flags 位标记（1 普通 / 2 商家 / 4 代卖员 / 8 运营） */
    private Integer roleFlags;
    /** 是否运营人员：前端据此展示"运营后台/平台运营台"入口 */
    private Boolean isAdmin;
    /** 加入时间 */
    private LocalDateTime createdAt;
}
