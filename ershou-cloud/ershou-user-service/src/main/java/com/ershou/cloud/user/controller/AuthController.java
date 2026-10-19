package com.ershou.cloud.user.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.user.dto.LoginDTO;
import com.ershou.cloud.user.dto.SmsCodeDTO;
import com.ershou.cloud.user.service.UserService;
import com.ershou.cloud.user.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（与单体 `/api/v1/auth/**` 路径、入参、返回结构一致）。
 *
 * <p>网关路由：`/api/v1/auth/**` → `lb://ershou-user-service`（公开）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** 发送短信验证码（演示环境固定 123456，写 Redis db1 `ershou:user:sms:code:{phone}`） */
    @PostMapping("/sms-code")
    public Result<Void> smsCode(@Valid @RequestBody SmsCodeDTO dto) {
        userService.sendSmsCode(dto.getPhone());
        return Result.ok();
    }

    /** 手机号验证码登录：返回 `{token, user}`，token 写 Redis db0（与单体会话互通） */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    /** 退出登录（幂等） */
    @PostMapping("/logout")
    public Result<Void> logout() {
        userService.logout();
        return Result.ok();
    }
}
