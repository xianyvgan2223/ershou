package com.ershou.cloud.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.ershou.cloud.gateway.filter.AuthGlobalFilter;
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
 *   <li>规则：路由 {@code ershou-item-get}（= GET /api/v1/items/**），
 *       <b>按用户维度</b>限流：登录用户 key={@code u:<userId>}，匿名 key={@code ip:<clientIp>}；</li>
 *   <li>阈值：每个 key QPS=5，代码内置（Nacos {@code ershou.gateway.item-qps} 可改，需重启）；</li>
 *   <li>超限响应：{@link SentinelGatewayBlockHandler} 统一写 HTTP 429 +
 *       {@code {"code":429,"message":"请求过于频繁，请稍后重试"}}；</li>
 *   <li>Dashboard：Nacos 里 {@code spring.cloud.sentinel.transport.dashboard=127.0.0.1:8858}，
 *       Sentinel Dashboard 上能看到资源与规则。</li>
 * </ul>
 * 说明：Spring Cloud Alibaba 2023.0.3.2 未提供 SCG 自动装配，SentinelGatewayFilter 由这里手工注册；
 * Sentinel 默认 order=HIGHEST_PRECEDENCE，会比 AuthGlobalFilter(-100) 更早执行而读不到
 * {@code X-Rate-Key}，所以这里显式传 -50，确保鉴权先注入用户/IP 维度。
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
        GatewayParamFlowItem param = new GatewayParamFlowItem()
                .setParseStrategy(SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HEADER)
                .setFieldName(AuthGlobalFilter.RATE_KEY_HEADER)
                .setMatchStrategy(SentinelGatewayConstants.PARAM_MATCH_STRATEGY_EXACT);

        Set<GatewayFlowRule> rules = new HashSet<>();
        rules.add(new GatewayFlowRule(ITEM_GET_ROUTE_ID)
                .setCount(itemQps)
                .setIntervalSec(1)
                .setParamItem(param));
        GatewayRuleManager.loadRules(rules);
        log.info("[gateway][sentinel] 已加载按用户限流规则：{} {} QPS={}",
                ITEM_GET_ROUTE_ID, AuthGlobalFilter.RATE_KEY_HEADER, itemQps);
    }

    @Bean
    public SentinelGatewayFilter sentinelGatewayFilter() {
        // -50：晚于 AuthGlobalFilter(-100)，早于路由转发；否则读不到 X-Rate-Key
        return new SentinelGatewayFilter(-50);
    }
}
