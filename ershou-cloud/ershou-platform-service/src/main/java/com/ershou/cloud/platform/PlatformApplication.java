package com.ershou.cloud.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 平台服务启动类（第 8 轮全量拆分 T106，端口 8086）。
 *
 * <p>调用关系：
 * <ul>
 *   <li>平台商品（item 表）→ item-service {@code /inner/items/platform/**}（Feign）；</li>
 *   <li>平台订单（meetup_order）→ order-service {@code /inner/orders/platform/**}（Feign）；</li>
 *   <li>管理员判定/用户昵称/校区名 → user-service {@code /inner/users/**}、{@code /inner/campuses}；</li>
 *   <li>自己的库 ershou_platform：代卖点 / 券 / 秒杀场次与记录 / 缓存统计；</li>
 *   <li>身份：网关注入 {@code X-User-Id}，common 的 {@code UserContextInterceptor} 写入 UserContext。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.ershou.cloud.platform.feign")
@MapperScan("com.ershou.cloud.platform.mapper")
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
