package com.ershou.cloud.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * TraceId（有则复用、无则生成），并在请求/响应头透传 {@code X-Trace-Id}。
 *
 * <p>实现成 WebFilter（而不是 Gateway GlobalFilter）：WebFilter 在路由之前执行，
 * 可以覆盖包括「Sa-Token 401」在内的所有响应；下游服务 common 的 TraceIdFilter 会沿用该头，
 * 于是 网关 → user/item/order 的日志能用同一个 traceId 串起来。
 */
@Component
public class TraceIdGlobalFilter implements WebFilter, Ordered {

    /** exchange attribute key：Sa-Token 401 JSON 也会带上这个 traceId */
    public static final String ATTR_TRACE_ID = "ershou.gateway.traceId";

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        exchange.getAttributes().put(ATTR_TRACE_ID, traceId);
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .header(TRACE_ID_HEADER, traceId)
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
