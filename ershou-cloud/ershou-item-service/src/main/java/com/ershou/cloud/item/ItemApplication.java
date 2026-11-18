package com.ershou.cloud.item;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商品服务（:8082）—— T102 全量拆分后的商品域微服务。
 *
 * <p>职责（契约 {@code ershou-cloud/docs/full-split-contract.md}）：
 * <ul>
 *   <li>对外（网关 8088 路由）：{@code /api/v1/items/**}（列表/详情/发布/编辑/下架/重上架/我的发布）、
 *       {@code /api/v1/categories/**}、{@code /api/v1/search/**}（ES 搜索状态/重建）；</li>
 *   <li>内部 API：{@code /inner/items/**}（详情/批量/点位商品/统计/状态/平台商品/面交锁库）、
 *       {@code /inner/item-reviews/**}（审核队列，供 admin-service）；</li>
 *   <li>ES 索引维护（发布/编辑/下架 afterCommit 同步 + 增量兜底 + 启动重建 + 不可用降级 MySQL）；
 *       RabbitMQ 消费 {@code order.created} 累加 {@code sale_count}；定时任务（到期下架/浏览量回写）。</li>
 * </ul>
 *
 * <p>数据库独立 schema {@code ershou_item}（item/item_category/item_review），Redis db2；
 * 跨域数据（卖家/点位/挂单）一律 OpenFeign 只读，不跨库 SQL。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.ershou.cloud.item.feign")
@EnableScheduling
public class ItemApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItemApplication.class, args);
    }
}
