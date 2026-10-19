package com.ershou.cloud.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 发送验证码入参（与单体 `module/user/dto/SmsCodeDTO` 一致） */
@Data
public class SmsCodeDTO {
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
