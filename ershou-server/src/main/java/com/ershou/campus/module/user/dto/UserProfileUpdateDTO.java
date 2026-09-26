package com.ershou.campus.module.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑个人资料入参（学生端「我的 → 编辑资料」）。
 *
 * <p>字段全部可选，只更新传了的字段（null = 不改）。头像 / 学校校区 / 小铺名 / 小铺简介
 * 都允许在这里改；头像、小铺名、小铺简介传空串表示清空。
 *
 * <p>手机号是登录凭证（加密 + 唯一索引），不开放修改；PRD BR-08 明确不收集真实姓名，
 * 所以这里的"姓名"就是昵称。
 */
@Data
public class UserProfileUpdateDTO {

    @Size(max = 32, message = "昵称不能超过 32 个字")
    private String nickname;

    @Size(max = 255, message = "头像地址过长")
    private String avatar;

    private Long schoolId;

    private Long campusId;

    @Size(max = 64, message = "小铺名称不能超过 64 个字")
    private String shopName;

    @Size(max = 255, message = "小铺简介不能超过 255 个字")
    private String shopIntro;
}
