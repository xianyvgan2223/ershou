package com.ershou.cloud.map.config;

/**
 * 地图服务 Redis key 集中出口（契约：Redis db5，前缀 {@code ershou:map:}；不跨服务共用业务 key）。
 *
 * <p>版本号整体失效策略沿用单体：写点位（新增/审核/启停/清理）后自增 {@link #pointsVersion()}，
 * 所有带 {@code v{version}} 的缓存/索引 key 立即不可达，下次请求按 DB 重建。
 */
public final class MapCacheKeys {

    private MapCacheKeys() {
    }

    /** 点位缓存版本号（常驻 key，写点位时 INCR） */
    public static String pointsVersion() {
        return "ershou:map:points:ver";
    }

    /** 校区点位基础列表（trade_point 本库数据，不含商品统计） */
    public static String basePoints(long campusId, long version) {
        return "ershou:map:base:" + campusId + ":v" + version;
    }

    /** 校区点位商品统计（Feign item-service 的 count-by-point 结果；单独 TTL，不随点位版本失效） */
    public static String itemStats(long campusId) {
        return "ershou:map:itemstats:" + campusId;
    }

    /** 附近点位 GEO 索引（member = pointId） */
    public static String geoIndex(long campusId, long version) {
        return "ershou:map:geo:" + campusId + ":v" + version;
    }

    /** GEO 重建分布式锁（SETNX） */
    public static String geoRebuildLock(long campusId) {
        return "ershou:map:geo:lock:" + campusId;
    }
}
