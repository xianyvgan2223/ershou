package com.ershou.cloud.gateway.config;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Sentinel 网关限流异常 → HTTP 429 JSON（第 5 轮设计 4.5 / Lead 派工）。
 *
 * <p>为什么自己实现 WebExceptionHandler：
 * Spring Cloud Alibaba 的 {@code SentinelWebFluxAutoConfiguration} 自带一个
 * {@code sentinelBlockExceptionHandler}（bean 名固定），处理 Gateway 的 BlockException 时
 * 不一定给出 429 JSON；这里用最高优先级抢在它前面，确保超限响应是
 * {@code 429 {"code":429,"message":"请求过于频繁，请稍后重试"}}。
 * bean 名特意叫 {@code sentinelGatewayBlockHandler}，避免与 SCA 自动配置的 bean 重名冲突。
 */
@Component
public class SentinelGatewayBlockHandler implements WebExceptionHandler, Ordered {

    private static final String BODY = "{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\",\"data\":null}";

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (!isBlockException(ex)) {
            return Mono.error(ex);
        }
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** Sentinel 的 BlockException 可能被 Reactor/Gateway 包了一层，沿 cause 链找 */
    private boolean isBlockException(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            if (cur instanceof BlockException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
