package com.ershou.cloud.order.feign;

import lombok.Data;

/**
 * user-service 内部接口返回的用户数据（契约见 full-split-contract 第三节）。
 * 面交单 VO 的买/卖家昵称、信用分流水的前后分都靠它。
 */
@Data
public class InnerUserVO {

    private Long id;

    private String nickname;

    private String avatar;

    private Integer creditScore;

    private Integer creditLevel;

    private Integer identityFlag;

    private Integer roleFlags;

    private Boolean isAdmin;

    private Integer status;

    private String shopName;

    private String schoolName;

    private String campusName;
}
