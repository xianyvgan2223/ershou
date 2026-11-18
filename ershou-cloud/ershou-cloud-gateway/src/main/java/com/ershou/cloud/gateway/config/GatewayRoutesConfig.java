package com.ershou.cloud.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由（全量微服务拆分版）。
 *
 * <p>旧单体 {@code 8080} 不再被网关反代：所有 {@code /api/v1/**} 与 {@code /ws/**} 均路由到对应微服务。
 * 路由按声明顺序匹配，因此前缀更具体的路由必须放在通配之前。
 *
 * <p>服务划分：
 * <ul>
 *   <li>user-service：认证、学校、用户（含公开主页 / 个人资料 / 黑名单）</li>
 *   <li>item-service：商品读写、分类、搜索（含“我的发布”）</li>
 *   <li>order-service：购物车、收藏、面交单（含状态流转/待办）</li>
 *   <li>chat-service：私聊 HTTP 接口 + WebSocket</li>
 *   <li>map-service：校内地图、面交点、附近点位</li>
 *   <li>platform-service：平台代卖、优惠券、秒杀、平台运营台</li>
 *   <li>admin-service：管理后台（审核/用户/商品/面交单/日志）</li>
 *   <li>file-service：MinIO 上传</li>
 * </ul>
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // ---- 用户服务：认证 / 学校 / 用户（全部） ----
                .route("ershou-user-auth", r -> r.path("/api/v1/auth/**").uri("lb://ershou-user-service"))
                .route("ershou-user-schools", r -> r.path("/api/v1/schools/**").uri("lb://ershou-user-service"))
                .route("ershou-user", r -> r.path("/api/v1/users/**").uri("lb://ershou-user-service"))
                // ---- 商品服务：分类 / 商品读写 / 搜索 ----
                .route("ershou-item-categories", r -> r.path("/api/v1/categories", "/api/v1/categories/**")
                        .uri("lb://ershou-item-service"))
                .route("ershou-item", r -> r.path("/api/v1/items/**").uri("lb://ershou-item-service"))
                .route("ershou-item-search", r -> r.path("/api/v1/search/**").uri("lb://ershou-item-service"))
                // ---- 订单服务：购物车 / 收藏 / 面交单 ----
                .route("ershou-order-cart", r -> r.path("/api/v1/cart/**").uri("lb://ershou-order-service"))
                .route("ershou-order-favorites", r -> r.path("/api/v1/favorites/**").uri("lb://ershou-order-service"))
                .route("ershou-order-meetup", r -> r.path("/api/v1/meetup-orders/**").uri("lb://ershou-order-service"))
                // ---- 聊天服务：HTTP + WebSocket（WS 鉴权由 chat-service 自己处理 ?token=） ----
                .route("ershou-chat", r -> r.path("/api/v1/chat/**").uri("lb://ershou-chat-service"))
                .route("ershou-admin-ws", r -> r.path("/ws/admin/**").uri("lb://ershou-admin-service"))
                .route("ershou-chat-ws", r -> r.path("/ws", "/ws/**").uri("lb://ershou-chat-service"))
                // ---- 地图服务 ----
                .route("ershou-map", r -> r.path("/api/v1/map/**", "/api/v1/trade-points/**")
                        .uri("lb://ershou-map-service"))
                // ---- 平台服务 ----
                .route("ershou-platform", r -> r.path("/api/v1/platform/**").uri("lb://ershou-platform-service"))
                // ---- 管理后台服务 ----
                .route("ershou-admin", r -> r.path("/api/v1/admin/**").uri("lb://ershou-admin-service"))
                // ---- 文件服务 ----
                .route("ershou-file", r -> r.path("/api/v1/files/**").uri("lb://ershou-file-service"))
                .build();
    }
}
