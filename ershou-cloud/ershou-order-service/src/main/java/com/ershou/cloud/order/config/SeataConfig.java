package com.ershou.cloud.order.config;

import org.springframework.context.annotation.Configuration;

/**
 * Seata AT 客户端说明（设计 5.1 / 五、Seata）。
 *
 * <p>本类<b>刻意不放任何 Bean</b>：Seata 的 TM/RM 与数据源代理由
 * {@code spring-cloud-starter-alibaba-seata}（内含 {@code seata-spring-boot-starter}）自动装配，
 * 数据源代理模式由 Nacos 配置 {@code ershou-order-service.yaml} 里的
 * {@code seata.data-source-proxy-mode: AT}、{@code seata.tx-service-group: ershou_tx_group} 决定；
 * 手动再定义 {@code DataSourceProxy}/{@code GlobalTransactionScanner} 会和自动装配冲突（Bean 重复）。
 *
 * <p>本服务侧要点（代码里已落实）：
 * <ul>
 *   <li>建单方法 {@code MeetupOrderService#create} 标 {@code @GlobalTransactional} ——
 *       TM 在 order-service，全局事务名 {@code create-meetup-order}；</li>
 *   <li>Feign 调用 item-service 的 {@code PUT /inner/items/{id}/lock} 作为 AT 分支事务注册，
 *       失败/后续抛异常时由 {@code ershou_item.undo_log} 自动回滚库存；</li>
 *   <li>order-service 自己写 {@code ershou_order.meetup_order}，回滚依赖 {@code ershou_order.undo_log}（两库表均已建）。</li>
 * </ul>
 *
 * <p>演示路径：{@code POST /api/v1/meetup-orders {debugFail:true}} → 扣库存后抛异常 →
 * TC 回滚全局事务 → item 库存不变、订单不落库。
 */
@Configuration
public class SeataConfig {
}
