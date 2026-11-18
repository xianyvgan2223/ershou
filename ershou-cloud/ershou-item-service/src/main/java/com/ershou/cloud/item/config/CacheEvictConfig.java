package com.ershou.cloud.item.config;

import com.ershou.cloud.common.cache.CacheEvictChannel;
import com.ershou.cloud.item.support.cache.JsonCache;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * L2 本地缓存失效广播：同服务多实例共享 Redis Pub/Sub。
 * 只处理 {@code item|} 前缀；{@code item|*} 清空全部本地缓存。
 */
@Configuration
@RequiredArgsConstructor
public class CacheEvictConfig {

    private final JsonCache cache;

    @Bean
    public RedisMessageListenerContainer cacheEvictContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            int idx = body.indexOf('|');
            if (idx <= 0) {
                return;
            }
            if (!CacheEvictChannel.SCOPE_ITEM.equals(body.substring(0, idx))) {
                return;
            }
            String key = body.substring(idx + 1);
            if (CacheEvictChannel.ALL_KEYS.equals(key)) {
                cache.clearLocal();
            } else {
                cache.invalidateLocal(List.of(key));
            }
        }, new ChannelTopic(CacheEvictChannel.NAME));
        return container;
    }
}
