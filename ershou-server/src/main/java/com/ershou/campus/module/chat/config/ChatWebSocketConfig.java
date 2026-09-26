package com.ershou.campus.module.chat.config;

import com.ershou.campus.module.chat.ws.ChatHandshakeInterceptor;
import com.ershou.campus.module.chat.ws.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 私聊 WebSocket 入口：ws://host/ws/chat?token=xxx
 * 开发环境前端在 5173、后端在 8080，属跨域握手，这里放开来源；
 * 生产由 Nginx 同源反代 /ws（开发文档 12 章部署配置）。
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
