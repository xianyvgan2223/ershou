package com.ershou.cloud.chat;

import com.ershou.cloud.chat.config.ChatProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 私聊微服务（:8084）启动类（第 7 轮全量拆分 T104）。
 *
 * <ul>
 *   <li>本服务只扫 {@code com.ershou.cloud.chat}；common 的统一返回/异常/TraceId/UserContext 由
 *       {@code CloudCommonAutoConfiguration} 自动装配；</li>
 *   <li>{@code @EnableFeignClients} 只扫本服务 feign 包（用户/商品展示信息）；</li>
 *   <li>{@code @MapperScan} 扫本服务 mapper 包（MyBatis-Plus）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.ershou.cloud.chat.feign")
@EnableConfigurationProperties(ChatProperties.class)
@MapperScan("com.ershou.cloud.chat.mapper")
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
