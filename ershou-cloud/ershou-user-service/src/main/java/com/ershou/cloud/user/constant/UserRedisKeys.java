package com.ershou.cloud.user.constant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * user-service 的 Redis key 统一出口（**全部限制在 db1，前缀 `ershou:user:`**）。
 *
 * <p>Redis 隔离约定（设计 2.2）：user-service 业务 key 只允许 `ershou:user:` 前缀、只写 db1；
 * Sa-Token 会话是唯一例外（必须写 db0 与单体/网关共享），由 `SaTokenRedisConfig` 单独配置连接。
 */
public final class UserRedisKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private UserRedisKeys() {
    }

    /** 短信验证码（mock 码也写这里，便于和真实短信走同一套校验） */
    public static String smsCode(String phone) {
        return "ershou:user:sms:code:" + phone;
    }

    /** 发码次数限制：同一手机号每天最多 N 次 */
    public static String smsLimit(String phone) {
        return "ershou:user:sms:limit:" + phone + ":" + LocalDate.now().format(DAY);
    }
}
