package com.ershou.campus.common.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 读缓存参数（application.yml 的 ershou.cache.*）。
 *
 * <p>enabled=false 时所有缓存读写都退化成"永远未命中"，等于一键回滚到直连 DB，
 * 缓存永远不参与业务正确性。
 */
@Data
@ConfigurationProperties(prefix = "ershou.cache")
public class CacheProperties {

    /** 总开关：线上缓存出问题时可以一键关掉，不影响业务 */
    private boolean enabled = true;

    /** 商品详情缓存 TTL（开发文档 6.2：ershou:item:detail:{itemId}） */
    private Duration itemDetailTtl = Duration.ofMinutes(10);

    /** 空值缓存 TTL（防穿透），商品不存在时写这个 */
    private Duration nullValueTtl = Duration.ofSeconds(60);

    /** 地图点位（含每个点位的在售数 / 最低价）缓存 TTL（开发文档 6.2：ershou:map:points） */
    private Duration mapPointsTtl = Duration.ofMinutes(30);

    /** 分类 / 学校 / 校区 / 点位等基础字典缓存 TTL（改动极少，写路径会主动失效） */
    private Duration dictTtl = Duration.ofHours(1);

    /** 运营概览统计缓存 TTL：统计允许秒级延迟，没必要每次 8 个 count(*) */
    private Duration dashboardTtl = Duration.ofSeconds(30);

    /** 热点 key 失效瞬间，没抢到重建锁的请求等待多久再读一次缓存 */
    private long rebuildWaitMillis = 80;
}
