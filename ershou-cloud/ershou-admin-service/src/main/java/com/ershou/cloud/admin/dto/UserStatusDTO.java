package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 用户状态：1 正常 / 2 限权 / 3 封禁；封禁可带天数（为空 = 永久） */
@Data
public class UserStatusDTO {
    @NotNull(message = "请指定用户状态")
    private Integer status;
    private Integer banDays;
    @Size(max = 200, message = "原因不能超过 200 字")
    private String reason;
}
