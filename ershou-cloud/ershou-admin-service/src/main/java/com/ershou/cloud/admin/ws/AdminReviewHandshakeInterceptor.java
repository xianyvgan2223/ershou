package com.ershou.cloud.admin.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.cloud.admin.service.AdminAuthService;
import lombok.RequiredArgsConstructor;
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
 * 上架审核 WS 握手鉴权：复用 Sa-Token 共享会话（Redis db0）+ user-service 运营身份校验。
 *
 * <p>为什么这里要自己解析 token：{@code /ws/**} 不经过网关的 Sa-Token WebFilter 注入 X-User-Id，
 * 浏览器 WebSocket 又不能带自定义头 —— 前端只能把 token 放 query（与旧单体实现一致）。
 * 所以 admin-service 引入 sa-token-redis-jackson 只用于这一处：确认登录身份后再调 inner 判管理员。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminReviewHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "reviewUserId";
    private static final String BEARER = "Bearer ";

    private final AdminAuthService adminAuthService;

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
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Long userId;
        try {
            userId = Long.valueOf(loginId.toString());
        } catch (NumberFormatException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            if (!adminAuthService.isAdmin(userId)) {
                log.warn("[admin][review][ws][{}] 非运营账号尝试连接审核推送，已拒绝", userId);
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
        } catch (Exception e) {
            // user-service 不可用时无法判权：拒绝连接（WS 非核心链路，前端会退化成手动刷新）
            log.warn("[admin][review][ws][{}] 管理员判定失败，拒绝连接：{}", userId, e.getMessage());
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
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
