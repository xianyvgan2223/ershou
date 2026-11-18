package com.ershou.cloud.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 运营后台配置（Nacos dataId ershou-admin-service.yaml 的 {@code ershou.admin.*}）。
 *
 * <p>管理员权威判定在 user-service（{@code /inner/users/{id}} 的 isAdmin/roleFlags），
 * 这里的 {@code roleFlag} 只在 Feign 不可用且拿到用户信息时做本地兜底。
 */
@Data
@ConfigurationProperties(prefix = "ershou.admin")
public class AdminProperties {

    /** role_flags 位标记：命中即视为运营（与单体一致，默认 8） */
    private int roleFlag = 8;

    /**
     * 应急/演示兜底管理员 id 白名单（默认空 = 关闭）。
     *
     * <p>只有 user-service 的 /inner 判定不可用（降级）时才会使用；生产不要配置。
     */
    private List<Long> bootstrapUserIds = new ArrayList<>();
}
