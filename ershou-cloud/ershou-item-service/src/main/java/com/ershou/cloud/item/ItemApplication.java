package com.ershou.cloud.item;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 商品服务（:8082）—— 第 5 轮 Spring Cloud 重构的「商品读 + 内部库存锁定 + MQ 消费」切片。
 *
 * <p>职责边界（设计 4.3 / 5.2）：
 * <ul>
 *   <li>公开读：{@code GET /api/v1/items}、{@code GET /api/v1/items/{id}}（网关把 GET 路由到本服务）；</li>
 *   <li>内部接口（不经网关，供 order-service OpenFeign 调用）：{@code GET /inner/items/{id}}、
 *       {@code PUT /inner/items/{id}/lock}；</li>
 *   <li>RabbitMQ 消费 {@code order.created}：异步累加 {@code sale_count}（演示 MQ 解耦）。</li>
 * </ul>
 *
 * <p>写路径（发布/编辑/下架）本轮**未迁移**，仍由旧单体 8080 处理（绞杀者模式，网关按方法分流）。
 * 数据库为独立 schema {@code ershou_item}，Redis 使用 db2，与单体隔离。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ItemApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItemApplication.class, args);
    }
}
