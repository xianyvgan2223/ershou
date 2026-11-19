package com.ershou.cloud.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 登录用户透传（UserIdHeaderFilter）。
 *
 * <p>Sa-Token 在 WebFilter 阶段完成鉴权，并把 userId 放入 exchange attribute；
 * 本过滤器在网关转发前把它写成 {@code X-User-Id} 请求头，供 user/item/order-service 读取
 * （common 的 UserContextInterceptor 会自动填到 UserContext）。
 *
 * <p><b>安全</b>：无论有没有登录用户，都先删掉客户端自带的 {@code X-User-Id}，
 * 防止前端/攻击者伪造身份头绕过网关。
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** exchange attribute key：SaTokenReactorConfig 写入，本类读取 */
    public static final String ATTR_USER_ID = "ershou.gateway.userId";

    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * 限流维度 header：登录用户写 {@code u:<userId>}，匿名写 {@code ip:<clientIp>}。
     * Sentinel 参数限流按这个 header 做 key，因此同一用户（或同一 IP）共享 QPS。
     */
    public static final String RATE_KEY_HEADER = "X-Rate-Key";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Long userId = (Long) exchange.getAttributes().get(ATTR_USER_ID);
        String rateKey = userId != null ? ("u:" + userId) : ("ip:" + clientIp(exchange));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(RATE_KEY_HEADER);
                    if (userId != null) {
                        headers.set(USER_ID_HEADER, String.valueOf(userId));
                    }
                    headers.set(RATE_KEY_HEADER, rateKey);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    /** Nginx 会写 X-Real-IP；直连网关时退回 TCP 对端地址 */
    private String clientIp(ServerWebExchange exchange) {
        String ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
