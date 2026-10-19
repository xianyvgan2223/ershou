package com.ershou.cloud.gateway.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpUtil;
import com.ershou.cloud.gateway.filter.AuthGlobalFilter;
import com.ershou.cloud.gateway.filter.TraceIdGlobalFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

/**
 * Sa-Token Reactor 鉴权（第 5 轮设计 4.1 / Lead 派工）。
 *
 * <p>与旧单体共用 Redis <b>db0</b>、同 token-name=Authorization / token-prefix=Bearer，
 * 所以单体或 user-service 登录拿到的 token 网关直接认，反之亦然（登录态互通）。
 *
 * <p>职责拆成两段（Reactor 下 SaTokenContext 是 ThreadLocal 的，GlobalFilter 里不能直接调 StpUtil）：
 * <ol>
 *   <li>本类注册 {@link SaReactorFilter}：WebFilter 阶段完成「公开路径放行 / 其余校验登录」，
 *       并把登录用户 id 放进 exchange attributes；未登录返回 <b>401</b> JSON（code=10002）；</li>
 *   <li>{@link AuthGlobalFilter}：Gateway GlobalFilter 阶段读 attribute，把 userId 写入转发请求头
 *       {@code X-User-Id}（同时清除客户端伪造的同名头）。</li>
 * </ol>
 */
@Slf4j
@Configuration
public class SaTokenReactorConfig {

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/**")
                .addExclude("/favicon.ico", "/error")
                .setAuth(obj -> {
                    ServerWebExchange exchange = currentExchange();
                    if (exchange == null) {
                        return;
                    }
                    String path = exchange.getRequest().getPath().value();
                    String method = exchange.getRequest().getMethod() == null
                            ? "GET" : exchange.getRequest().getMethod().name();
                    boolean logged = StpUtil.isLogin();
                    if (!logged && !PublicPaths.isPublic(method, path)) {
                        // 抛 NotLoginException，由下方 setError 统一转 401 JSON
                        StpUtil.checkLogin();
                    }
                    if (logged) {
                        exchange.getAttributes().put(AuthGlobalFilter.ATTR_USER_ID, StpUtil.getLoginIdAsLong());
                    }
                })
                .setError(e -> {
                    SaHolder.getResponse().setHeader("Content-Type", "application/json;charset=UTF-8");
                    SaHolder.getResponse().setStatus(401);
                    String traceId = currentTraceId();
                    return "{\"code\":10002,\"message\":\"未登录或登录已过期\",\"data\":null"
                            + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"") + "}";
                });
    }

    /** 当前请求的 exchange：Sa-Token reactor 的 SaStorage 底层就是 ServerWebExchange */
    private ServerWebExchange currentExchange() {
        Object source = SaHolder.getStorage().getSource();
        return source instanceof ServerWebExchange exchange ? exchange : null;
    }

    private String currentTraceId() {
        ServerWebExchange exchange = currentExchange();
        if (exchange == null) {
            return null;
        }
        Object traceId = exchange.getAttributes().get(TraceIdGlobalFilter.ATTR_TRACE_ID);
        return traceId == null ? null : traceId.toString();
    }
}
