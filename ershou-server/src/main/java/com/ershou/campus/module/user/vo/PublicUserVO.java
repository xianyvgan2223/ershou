package com.ershou.campus.module.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外可见的用户主页（PRD FR-UP-04 / BR-08）。
 * 刻意不复用 UserVO：那个带脱敏手机号，主页是公开接口，不该出现任何联系方式。
 */
@Data
public class PublicUserVO {

    private Long id;
    private String nickname;
    private String avatar;
    private Integer creditScore;
    private Integer creditLevel;
    /** 1=商家（展示"商家"徽章） */
    private Integer identityFlag;
    private String shopName;
    private String schoolName;
    private String campusName;
    private LocalDateTime createdAt;
    /** 账号状态（3=封禁）：主页仍可访问，由前端提示 */
    private Integer status;
    /** 在售商品数 */
    private Long onSaleCount;
    /** 当前查看者是否已拉黑他 */
    private Boolean blocked;
    /** 他是否拉黑了当前查看者 */
    private Boolean blockedByPeer;
    /** 是否是我自己 */
    private Boolean self;
}
