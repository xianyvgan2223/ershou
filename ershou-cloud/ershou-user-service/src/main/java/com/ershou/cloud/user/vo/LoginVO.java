package com.ershou.cloud.user.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录响应（与单体 `module/user/vo/LoginVO` 一致）。
 *
 * <p>前端 `api/auth.ts#login` 直接读 `data.token` 和 `data.user`，结构不能变。
 */
@Data
@Builder
public class LoginVO {
    private String token;
    private UserVO user;
}
