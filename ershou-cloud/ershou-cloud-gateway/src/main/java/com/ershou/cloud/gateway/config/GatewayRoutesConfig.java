package com.ershou.cloud.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * 网关路由（第 5 轮设计 4.1，绞杀者模式）。
 *
 * <p>原则：已迁移的按服务前缀走 {@code lb://}；未迁移/写路径反代旧单体 {@code 8080}，功能不中断。
 * 路由按声明顺序匹配（Spring Cloud Gateway 给每个 route 递增 order），所以特例必须放在通配之前。
 *
 * <p>与设计/负责人确认后的两处调整（已报备）：
 * <ol>
 *   <li>{@code GET /api/v1/items/mine} 反代单体：我的发布仍是单体写路径，item-service 没有该接口，
 *       不能被 {@code GET /api/v1/items/**} 劫持（否则 "mine" 被当 Long 解析直接 400）；</li>
 *   <li>{@code /api/v1/categories} 路由到 item-service：分类表在 {@code ershou_item} 库，
 *       user-service 的库没有 item_category（item-member 已在 item-service 实现同结构接口）。</li>
 * </ol>
 */
@Configuration
public class GatewayRoutesConfig {

    /** 旧单体地址（未迁移路径反代；Nacos 可覆盖） */
    @Value("${ershou.gateway.legacy-uri:http://127.0.0.1:8080}")
    private String legacyUri;

    /** WebSocket（聊天暂留单体） */
    @Value("${ershou.gateway.ws-uri:ws://127.0.0.1:8080}")
    private String wsUri;

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // ---- 用户服务：认证 / 学校 / 当前用户 ----
                .route("ershou-user-auth", r -> r.path("/api/v1/auth/**").uri("lb://ershou-user-service"))
                .route("ershou-user-schools", r -> r.path("/api/v1/schools/**").uri("lb://ershou-user-service"))
                .route("ershou-user-me", r -> r.path("/api/v1/users/me").uri("lb://ershou-user-service"))
                // 其余 /users/**（公开主页、历史接口）仍走单体
                .route("ershou-user-legacy", r -> r.path("/api/v1/users/**").uri(legacyUri))
                // ---- 商品服务：分类 + 商品读；写路径/我的发布反代单体 ----
                .route("ershou-item-categories", r -> r.path("/api/v1/categories", "/api/v1/categories/**")
                        .uri("lb://ershou-item-service"))
                .route("ershou-item-mine", r -> r.method(HttpMethod.GET).and().path("/api/v1/items/mine")
                        .uri(legacyUri))
                .route("ershou-item-get", r -> r.method(HttpMethod.GET).and().path("/api/v1/items/**")
                        .uri("lb://ershou-item-service"))
                .route("ershou-item-write", r -> r.path("/api/v1/items/**").uri(legacyUri))
                // ---- 订单服务：面交单创建 + 我的列表（已迁移） ----
                .route("ershou-order-meetup", r -> r.path("/api/v1/meetup-orders/**")
                        .uri("lb://ershou-order-service"))
                // ---- WebSocket ----
                .route("ershou-legacy-ws", r -> r.path("/ws/**").uri(wsUri))
                // ---- 其余 /api/v1/**（admin/platform/map/files/search/cart/favorites…）反代单体 ----
                .route("ershou-legacy-api", r -> r.path("/api/v1/**").uri(legacyUri))
                .build();
    }
}
