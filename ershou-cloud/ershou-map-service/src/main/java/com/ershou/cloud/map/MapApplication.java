package com.ershou.cloud.map;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 地图/面交点服务（:8085，T105）启动类。
 *
 * <p>从旧单体 {@code module/map} 全量拆出（契约：{@code ershou-cloud/docs/full-split-contract.md}）：
 * <ul>
 *   <li>对外：{@code /api/v1/map/**}（地图底图+点位聚合、附近点位 GEO、逆地理反查、移动端地图 HTML）、
 *       {@code /api/v1/trade-points/**}（点位列表/推荐/新增/我的点位）；</li>
 *   <li>对内：{@code /inner/points/**}（点详情/列表/管理列表/stats/审核/启停/空点位清理）；</li>
 *   <li>依赖：item-service 提供点位商品数与列表（OpenFeign），user-service 提供校区底图配置；</li>
 *   <li>数据：MySQL {@code ershou_map.trade_point}；Redis db5（点位缓存 + GEO 索引，前缀 {@code ershou:map:}）。</li>
 * </ul>
 *
 * <p>不扫 {@code com.ershou.cloud.common}：公共能力（统一返回/异常/TraceId/UserContext）由 common 的
 * {@code CloudCommonAutoConfiguration} 自动装配；{@code @EnableFeignClients} 只扫本服务 feign 包。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.ershou.cloud.map.feign")
@MapperScan("com.ershou.cloud.map.mapper")
@EnableScheduling
public class MapApplication {

    public static void main(String[] args) {
        SpringApplication.run(MapApplication.class, args);
    }
}
