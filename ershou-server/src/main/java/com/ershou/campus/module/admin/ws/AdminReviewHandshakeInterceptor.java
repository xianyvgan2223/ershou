package com.ershou.campus.module.admin.ws;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.module.admin.service.AdminAuthService;
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
 * 上架审核 WS 握手鉴权：复用 Sa-Token 会话 + 运营身份校验。
 *
 * <p>与私聊握手的关键差别：这里<b>还要判运营身份</b>。私聊只要登录就能连，
 * 而审核队列里是"全站还没被人工看过的商品流"，普通用户连上就等于泄露了未经审核的内容。
 * 所以 {@code /ws/**} 不在 SaTokenConfig 拦截范围内，这里必须自己把两道校验都做完。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminReviewHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "reviewUserId";
    public static final String ATTR_NICKNAME = "reviewNickname";

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
        if (!adminAuthService.isAdmin(userId)) {
            // 403 而不是 401：身份没错，是权限不够
            log.warn("[admin][review][ws][{}] 非运营账号尝试连接审核推送，已拒绝", userId);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_NICKNAME, nickname(request));
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

    /** 昵称只是用来在"谁在看这条"里显示，取不到就用 userId 兜底 */
    private String nickname(ServerHttpRequest request) {
        String query = request.getURI().getQuery();
        if (!StringUtils.hasText(query)) {
            return null;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "nickname".equals(pair.substring(0, idx))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(idx + 1), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
