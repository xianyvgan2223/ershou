package com.ershou.cloud.item.support.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 读缓存参数（application.yml 的 ershou.cache.*）。
 *
 * <p>enabled=false 时所有缓存读写都退化成"永远未命中"，等于一键回滚到直连 DB，
 * 缓存永远不参与业务正确性。
 */
@Data
@Component
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

    /**
     * 热点 key 失效瞬间，没抢到重建锁的请求「睡多久再读一次缓存」。
     *
     * <p>⚠️ 这个值的语义在 2026-10-10 被修正过，别再当成"重试一次就放弃"：
     * 它现在是**轮询间隔**，配合 {@link #maxRebuildWaitMillis} 一起用 ——
     * 只要重建锁还在（说明别人正在回源），等待方就继续等，
     * 而不是"等 80ms 没等到就自己也去查库"。
     * 原来 80ms 单次等待的写法，在回源比 80ms 慢时**等于没防**（见 JsonCache 的说明）。
     */
    private long rebuildWaitMillis = 50;

    /**
     * 等待重建的上限：超过就自己回源（兜底，避免重建方卡死把大家都拖住）。
     *
     * <p>取值权衡：太小则"回源慢时又变成一起打库"，太大则重建异常时用户多等。
     * 地图点位聚合实测 ~180ms，商品详情 ~20ms，500ms 足够覆盖正常重建；
     * 真要更慢，说明 DB 本身有压力，这时让少量请求去排队反而更好。
     */
    private long maxRebuildWaitMillis = 500;
}
