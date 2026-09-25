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
}
