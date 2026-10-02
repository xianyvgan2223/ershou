package com.ershou.campus.module.admin.config;

import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.admin.service.ReviewPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 订阅上架审核广播频道：本实例只管把别的实例发来的事件推给自己机器上的审核员连接 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AdminReviewRedisConfig {

    private final ReviewPushService reviewPushService;

    @Bean
    public RedisMessageListenerContainer adminReviewListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                        reviewPushService.onRemoteMessage(ReviewPushService.decode(message.getBody())),
                new ChannelTopic(RedisKeys.adminReviewChannel()));
        log.info("[admin][review][redis] 已订阅上架审核广播频道 {}", RedisKeys.adminReviewChannel());
        return container;
    }
}
