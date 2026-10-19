package com.ershou.cloud.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 面交单微服务（:8083）启动类。
 *
 * <p>第 5 轮 Spring Cloud 重构切片：面交单创建/我的列表从单体迁出，
 * 演示 OpenFeign（服务间调用）、Seata AT（跨库事务回滚）、RabbitMQ（事务后发事件）。
 *
 * <ul>
 *   <li>不额外写 {@code scanBasePackages}：本服务只扫 {@code com.ershou.cloud.order}；
 *       {@code com.ershou.cloud.common} 的公共能力（统一返回/异常/TraceId/UserContext）由
 *       {@code CloudCommonAutoConfiguration}（common 的 AutoConfiguration.imports）自动装配，
 *       这样不会和自动装配重复注册 Bean；</li>
 *   <li>{@code @EnableFeignClients} 只扫本服务的 feign 包，避免误扫其它服务的 FeignClient；</li>
 *   <li>{@code @MapperScan} 扫本服务 mapper 包（MyBatis-Plus）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.ershou.cloud.order.feign")
@MapperScan("com.ershou.cloud.order.mapper")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
