package com.ershou.cloud.item.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端（item-service，Redis db2）。
 *
 * <p>只用于分布式锁（缓存重建锁），缓存值仍走 {@code StringRedisTemplate}，两套连接互不影响。
 * 锁名带 Redisson 后缀，避免与旧 SETNX 字符串 key 冲突（旧 key 会自然过期）。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setTimeout(3000);
        if (StringUtils.hasText(password)) {
            config.useSingleServer().setPassword(password);
        }
        return Redisson.create(config);
    }
}
