package com.ershou.cloud.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * API 网关（:8088）：前端/App 的统一入口。
 *
 * <p>职责：路由（已迁移服务走 lb://，未迁移路径反代旧单体 8080）、Sa-Token 鉴权
 * （共享 Redis db0，与单体登录态互通）、注入 X-User-Id、Sentinel 限流、CORS、TraceId。
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
