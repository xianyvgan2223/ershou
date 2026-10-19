package com.ershou.cloud.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户域微服务（认证 / 用户 / 学校校区），端口 8081。
 *
 * <p>第 5 轮 Spring Cloud 重构：从单体 `ershou-server` 的 `module/user` + `module/school`
 * 裁剪出来的可独立部署切片。接口路径、字段结构、Sa-Token 配置都与单体保持一致，
 * 这样 Web / App 不用改任何代码（只改网关地址）。
 *
 * <p>配置来源：本地 `application.yml` 只有端口/服务名/Nacos 地址，数据源、Redis、Sa-Token
 * 全部来自 Nacos 配置中心（dataId `ershou-user-service.yaml`），用于演示配置中心能力。
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.ershou.cloud.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
