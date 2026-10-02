package com.ershou.campus.module.admin.config;

import com.ershou.campus.module.admin.ws.AdminReviewHandshakeInterceptor;
import com.ershou.campus.module.admin.ws.AdminReviewWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 上架审核实时推送入口：ws://host/ws/admin/review?token=xxx&nickname=xxx
 *
 * <p>与私聊 WS 分开注册（不同的 handler/拦截器）：私聊是"登录即可"，审核推送要额外判运营身份，
 * 两者的鉴权强度不同，混在一个 handler 里迟早出错。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class AdminReviewWebSocketConfig implements WebSocketConfigurer {

    private final AdminReviewWebSocketHandler reviewWebSocketHandler;
    private final AdminReviewHandshakeInterceptor reviewHandshakeInterceptor;
    private final ReviewProperties reviewProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (!reviewProperties.isRealtimeEnabled()) {
            return;
        }
        registry.addHandler(reviewWebSocketHandler, "/ws/admin/review")
                .addInterceptors(reviewHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
