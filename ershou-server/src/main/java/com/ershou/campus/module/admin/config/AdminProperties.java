package com.ershou.campus.module.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 运营后台配置，对应 application.yml 的 ershou.admin.*
 *
 * <p>管理员判定有两个来源：① {@code sys_user.role_flags} 命中 {@link #roleFlag} 位（默认 8=运营）；
 * ② {@link #userIds} 白名单（本地开发/应急用，可通过环境变量 ERSHOU_ADMIN_USER_IDS=1,2 注入）。
 */
@Data
@ConfigurationProperties(prefix = "ershou.admin")
public class AdminProperties {

    /** 管理员用户 id 白名单 */
    private List<Long> userIds = new ArrayList<>();

    /** 运营位：sys_user.role_flags 位与命中即视为管理员 */
    private int roleFlag = 8;
}
