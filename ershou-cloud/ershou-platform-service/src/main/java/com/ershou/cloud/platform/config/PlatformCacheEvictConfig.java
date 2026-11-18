package com.ershou.cloud.platform.config;

import com.ershou.cloud.common.cache.CacheEvictChannel;
import com.ershou.cloud.platform.cache.PlatformJsonCache;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * L2 本地缓存失效广播：只处理 {@code platform|} 前缀；{@code platform|*} 清空全部本地缓存。
 */
@Configuration
@RequiredArgsConstructor
public class PlatformCacheEvictConfig {

    private final PlatformJsonCache cache;

    @Bean
    public RedisMessageListenerContainer platformCacheEvictContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            int idx = body.indexOf('|');
            if (idx <= 0) {
                return;
            }
            if (!CacheEvictChannel.SCOPE_PLATFORM.equals(body.substring(0, idx))) {
                return;
            }
            String key = body.substring(idx + 1);
            if (CacheEvictChannel.ALL_KEYS.equals(key)) {
                cache.clearLocal();
            } else {
                cache.deleteLocalOnly(List.of(key));
            }
        }, new ChannelTopic(CacheEvictChannel.NAME));
        return container;
    }
}
