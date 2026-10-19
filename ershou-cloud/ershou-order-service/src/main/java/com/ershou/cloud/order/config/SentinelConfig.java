package com.ershou.cloud.order.config;

import org.springframework.context.annotation.Configuration;

/**
 * Sentinel 降级说明（设计 4.5）。
 *
 * <p>本服务不代码加载限流规则（限流规则在 Gateway：{@code /api/v1/items} QPS=5）；
 * order-service 这里的 Sentinel 只用于 <b>Feign 熔断降级</b>：
 * Nacos 配置 {@code feign.sentinel.enabled=true} + {@code spring.cloud.sentinel.transport.dashboard=127.0.0.1:8858}
 * 后，{@code ItemClient} 调用失败/超时/熔断时走 {@code ItemClientFallback}，返回
 * {@code code=90001「商品服务暂不可用」}，不会把 500 直接抛给前端。
 *
 * <p>fallback 类必须是 Spring Bean（已用 {@code @Component}），并且
 * {@code @FeignClient(fallback = ItemClientFallback.class)} 指定；两者缺一不生效。
 * 规则如需在 Dashboard 调，可在 Dashboard 对资源 {@code GET:http://ershou-item-service/inner/items/{id}}
 * 添加流控/熔断规则（演示可选）。
 */
@Configuration
public class SentinelConfig {
}
