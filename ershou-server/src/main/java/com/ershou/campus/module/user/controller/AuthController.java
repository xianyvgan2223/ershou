package com.ershou.campus.module.user.controller;

import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.user.dto.LoginDTO;
import com.ershou.campus.module.user.dto.SmsCodeDTO;
import com.ershou.campus.module.user.service.UserService;
import com.ershou.campus.module.user.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "发送短信验证码（开发环境固定 123456）")
    @PostMapping("/sms-code")
    public Result<Void> smsCode(@Valid @RequestBody SmsCodeDTO dto) {
        userService.sendSmsCode(dto.getPhone());
        return Result.ok();
    }

    @Operation(summary = "手机号验证码登录（轻注册，无实名）")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public Result<Void> logout() {
        cn.dev33.satoken.stp.StpUtil.logout();
        return Result.ok();
    }
}
