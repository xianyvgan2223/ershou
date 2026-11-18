package com.ershou.cloud.chat.config;

import com.ershou.cloud.chat.service.ChatPushService;
import com.ershou.cloud.chat.util.ChatRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 订阅私聊广播频道：本实例只管把别的实例发来的消息推给自己机器上的连接（ADR-06）。 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChatRedisConfig {

    private final ChatPushService chatPushService;

    @Bean
    public RedisMessageListenerContainer chatMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) ->
                        chatPushService.onRemoteMessage(ChatPushService.decode(message.getBody())),
                new ChannelTopic(ChatRedisKeys.chatChannel()));
        log.info("[chat][redis] 已订阅私聊广播频道 {}", ChatRedisKeys.chatChannel());
        return container;
    }
}
