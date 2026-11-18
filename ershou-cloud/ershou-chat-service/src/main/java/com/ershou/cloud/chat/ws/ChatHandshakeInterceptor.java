package com.ershou.cloud.chat.ws;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WS 握手鉴权：复用 Sa-Token 会话（Redis db0 里的 token），不额外发一套凭证。
 * token 从 {@code ?token=xxx} 或 {@code Authorization: Bearer xxx} 取。
 *
 * <p>与旧单体 ChatHandshakeInterceptor 完全一致：/ws/** 不在网关/服务的 /api/** 鉴权范围内，
 * 所以这里必须自己校验（网关对 /ws/** 只做路由转发，不注入 X-User-Id）。
 */
@Slf4j
@Component
public class ChatHandshakeInterceptor implements HandshakeInterceptor {

    private static final String BEARER = "Bearer ";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = token(request);
        if (!StringUtils.hasText(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Object loginId = StpUtil.getLoginIdByToken(token);
        if (loginId == null) {
            log.debug("[chat][ws] token 无效或已过期，拒绝握手");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ChatWebSocketHandler.ATTR_USER_ID, Long.valueOf(loginId.toString()));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private String token(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (StringUtils.hasText(query)) {
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                    return pair.substring(idx + 1);
                }
            }
        }
        String header = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER)) {
            return header.substring(BEARER.length()).trim();
        }
        return null;
    }
}
