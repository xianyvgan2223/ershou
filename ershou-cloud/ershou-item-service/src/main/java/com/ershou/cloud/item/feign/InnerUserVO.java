package com.ershou.cloud.item.feign;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * user-service 内部用户视图（契约 §三 user-service + T102 追加字段）。
 *
 * <p>字段按契约 + Lead 确认的追加：schoolId/campusId/createdAt/publishLimit/roleFlags。
 * item-service 用它：① 商品 VO 的 seller 摘要；② 发布时落 school/campus + 新号 7 天限额。
 */
@Data
public class InnerUserVO {
    private Long id;
    private String nickname;
    private String avatar;
    private Integer creditScore;
    private Integer identityFlag;
    private Integer roleFlags;
    private Boolean isAdmin;
    private Integer status;
    private String shopName;
    private String schoolName;
    private String campusName;
    // T102 追加
    private Long schoolId;
    private Long campusId;
    private LocalDateTime createdAt;
    private Integer publishLimit;
}
