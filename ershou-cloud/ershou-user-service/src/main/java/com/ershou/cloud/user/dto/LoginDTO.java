package com.ershou.cloud.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录入参（与单体 `module/user/dto/LoginDTO` 一致）。
 *
 * <p>`schoolId/campusId/deviceId` 都是可选：前端登录页只在选了学校校区时才传，
 * 不传时按默认学校/校区建号（与单体行为一致）。
 */
@Data
public class LoginDTO {
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "请输入验证码")
    private String code;

    private Long schoolId = 1L;
    private Long campusId = 1L;
    private String deviceId;
}
