package com.ershou.cloud.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 平台服务 L2 进程内缓存配置（{@code ershou.platform.cache.*}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ershou.platform.cache")
public class PlatformCacheProperties {

    /** 是否启用 L2 进程内缓存（Caffeine）；关闭即回滚到 Redis-only */
    private boolean localEnabled = true;

    /** L2 本地缓存 TTL：比 Redis 短，丢 Pub/Sub 消息时最多脏这么久 */
    private Duration localTtl = Duration.ofSeconds(5);

    /** L2 容量上限（字节，按 UTF-8 估算） */
    private long localMaxBytes = 32L * 1024 * 1024;

    /** 写路径是否通过 Redis Pub/Sub 广播本地缓存失效 */
    private boolean pubsubEnabled = true;
}
