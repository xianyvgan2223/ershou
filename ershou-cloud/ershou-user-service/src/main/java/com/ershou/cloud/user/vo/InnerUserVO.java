package com.ershou.cloud.user.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内部接口返回的用户视图（`GET /inner/users/{id}`、`GET /inner/users/batch`）。
 *
 * <p>字段是契约最小集 + 各服务实际依赖（Lead T101 追加）：
 * <ul>
 *   <li>契约：id/nickname/avatar/creditScore/identityFlag/roleFlags/isAdmin/status/schoolName/campusName；</li>
 *   <li>item-service 追加：schoolId/campusId/createdAt/publishLimit（发布归属、新号限额、卖家卡片）；</li>
 *   <li>order-service 追加：creditScore/identityFlag/roleFlags 已在契约内；</li>
 *   <li>admin-service 追加：isAdmin/roleFlags/status 已在契约内。</li>
 * </ul>
 *
 * <p>字段名 camelCase、语义与单体 `UserVO` 对齐，**但绝不含手机号**（内部接口只给展示所需最小集，
 * 手机号只在管理端列表 `/inner/users` 里以脱敏形式出现）。
 */
@Data
public class InnerUserVO {

    private Long id;
    private String nickname;
    private String avatar;
    private Long schoolId;
    private String schoolName;
    private Long campusId;
    private String campusName;
    private Integer creditScore;
    private Integer creditLevel;
    private String shopName;
    private String shopIntro;
    /** 1=商家 / 2=代卖员 / 4=校内邮箱已验证（位标记） */
    private Integer identityFlag;
    private Integer publishLimit;
    private Integer chatLimit;
    /** role_flags 位标记（1 普通 / 2 商家 / 4 代卖员 / 8 运营） */
    private Integer roleFlags;
    /** 是否运营人员（口径与单体 AdminAuthService 一致：白名单或 role_flags 命中运营位） */
    private Boolean isAdmin;
    /** 1=正常 2=限权 3=封禁 */
    private Integer status;
    /** 加入时间（item-service 卖家卡片/新号判断用） */
    private LocalDateTime createdAt;
}
