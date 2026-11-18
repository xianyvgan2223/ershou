package com.ershou.cloud.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 黑名单里的一条（`GET /api/v1/users/blocks`），与单体 `UserBlockVO` 字段一致。
 *
 * <p>只补列表展示必需的最小字段，**不含手机号等任何联系方式**（黑名单页没有理由比公开主页看到更多）。
 */
@Data
public class UserBlockVO {

    /** 拉黑记录 id */
    private Long id;

    /** 被拉黑的人 */
    private Long blockedUserId;
    private String nickname;
    private String avatar;
    private Integer creditScore;
    /** 1=商家 / 2=代卖员 / 4=校内邮箱已验证（位标记） */
    private Integer identityFlag;
    private String shopName;
    private String schoolName;
    private String campusName;

    /** 拉黑时间 */
    private LocalDateTime createdAt;

    /**
     * 对方是否也拉黑了我：双向拉黑时"解除"只是单方面解除，对方仍然拉黑着，
     * 页面要说清楚，否则用户会以为解除后就能正常私聊了。
     */
    private Boolean blockedByPeer;
}
