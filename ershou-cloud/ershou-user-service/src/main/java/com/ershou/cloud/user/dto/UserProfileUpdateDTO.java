package com.ershou.cloud.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑个人资料入参（`PATCH /api/v1/users/me`，与单体 `UserProfileUpdateDTO` 一致）。
 *
 * <p>字段全部可选：只更新传了的字段（null = 不改）；头像 / 小铺名 / 小铺简介传空串表示清空。
 * 手机号是登录凭证（密文 + `phone_hash` 唯一索引），不在这里改。
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
