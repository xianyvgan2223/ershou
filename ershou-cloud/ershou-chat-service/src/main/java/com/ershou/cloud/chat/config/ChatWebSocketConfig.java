package com.ershou.cloud.chat.config;

import com.ershou.cloud.chat.ws.ChatHandshakeInterceptor;
import com.ershou.cloud.chat.ws.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 私聊 WebSocket 入口：ws://host:8084/ws/chat?token=xxx
 *
 * <p>与旧单体完全同协议、同路径；网关 8088 的 {@code /ws/**} 已路由到本服务。
 * 请求来源放开（开发环境 Web 5173 / App 直连），鉴权由 {@link ChatHandshakeInterceptor} 做。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final ChatHandshakeInterceptor chatHandshakeInterceptor;
    private final ChatProperties chatProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (!chatProperties.isEnabled()) {
            return;
        }
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .addInterceptors(chatHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
