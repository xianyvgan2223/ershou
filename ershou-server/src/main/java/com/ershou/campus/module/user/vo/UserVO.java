package com.ershou.campus.module.user.vo;

import lombok.Data;

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
    private Integer identityFlag;
    private Integer publishLimit;
    private Integer chatLimit;
    private Boolean newUser;
    /** role_flags 位标记（1 普通 / 2 商家 / 4 代卖员 / 8 运营） */
    private Integer roleFlags;
    /** 是否运营人员：前端据此展示"运营后台"入口 */
    private Boolean isAdmin;
}
