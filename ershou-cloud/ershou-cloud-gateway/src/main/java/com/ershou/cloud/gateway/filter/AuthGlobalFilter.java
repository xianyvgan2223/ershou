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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Long userId = (Long) exchange.getAttributes().get(ATTR_USER_ID);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    if (userId != null) {
                        headers.set(USER_ID_HEADER, String.valueOf(userId));
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
