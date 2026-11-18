package com.ershou.cloud.admin.ws;

import com.ershou.cloud.admin.config.ReviewProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 上架审核实时推送入口：{@code ws://host/ws/admin/review?token=xxx}。
 *
 * <p>与旧单体一致：私聊 WS 在 chat-service，审核 WS 单独注册（鉴权强度不同：这里额外判运营身份）。
 * {@code realtime-enabled=false} 时整体不注册（页面自动退回手动刷新）。
 *
 * <p>注意 Origin 全放行与旧单体一致（前端 dev server 端口可能变化）；生产应收敛到具体域名。
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
