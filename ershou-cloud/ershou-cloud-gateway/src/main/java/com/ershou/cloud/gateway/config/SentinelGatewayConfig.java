package com.ershou.cloud.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * Sentinel 网关限流（第 5 轮设计 4.5 / Lead 派工）。
 *
 * <ul>
 *   <li>规则：路由 {@code ershou-item-get}（= GET /api/v1/items/**）QPS=5，代码内置（演示可改 Nacos item-qps）；</li>
 *   <li>超限响应：{@link SentinelGatewayBlockHandler} 统一写 HTTP 429 +
 *       {@code {"code":429,"message":"请求过于频繁，请稍后重试"}}；</li>
 *   <li>Dashboard：Nacos 里 {@code spring.cloud.sentinel.transport.dashboard=127.0.0.1:8858}，
 *       Sentinel Dashboard 上能看到资源与规则。</li>
 * </ul>
 * 说明：Spring Cloud Alibaba 2023.0.3.2 未提供 SCG 自动装配，SentinelGatewayFilter 由这里手工注册；
 * 429 响应不用官方 SentinelGatewayBlockExceptionHandler（它没有 Ordered、且 SCA WebFlux 自动配置占用了
 * 同名 bean），改用自实现且最高优先级的 {@link SentinelGatewayBlockHandler}。
 */
@Slf4j
@Configuration
public class SentinelGatewayConfig {

    @Value("${ershou.gateway.item-qps:5}")
    private double itemQps;

    /** 商品列表路由资源名（与 GatewayRoutesConfig 里的 route id 保持一致） */
    private static final String ITEM_GET_ROUTE_ID = "ershou-item-get";

    @PostConstruct
    public void initRules() {
        Set<GatewayFlowRule> rules = new HashSet<>();
        rules.add(new GatewayFlowRule(ITEM_GET_ROUTE_ID)
                .setCount(itemQps)
                .setIntervalSec(1));
        GatewayRuleManager.loadRules(rules);
        log.info("[gateway][sentinel] 已加载网关限流规则：{} QPS={}", ITEM_GET_ROUTE_ID, itemQps);
    }

    @Bean
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }
}
