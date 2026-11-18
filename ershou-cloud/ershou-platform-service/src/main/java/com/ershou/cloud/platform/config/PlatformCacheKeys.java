package com.ershou.cloud.platform.config;

/**
 * 平台代卖 Redis key 统一出口（第 2 轮设计第四节）。
 *
 * <p>刻意不改 {@code common/RedisKeys}：那边是全站公共 key 出口，
 * 平台代卖的 key 全带 {@code ershou:platform:} 前缀，集中在这里方便一处改、一处查。
 *
 * <p>key 命名沿用开发文档 6.1 约定：{@code ershou:{模块}:{业务}:{标识}}，全小写、冒号分隔。
 */
public final class PlatformCacheKeys {

    private PlatformCacheKeys() {
    }

    private static final String PREFIX = "ershou:platform:";

    /** 代卖点列表缓存（String/JSON，TTL 10min，后台增删改即删） */
    public static String points(Long campusId) {
        return PREFIX + "points:" + campusId;
    }

    /** 可领券列表缓存（String/JSON，TTL 60s） */
    public static String coupons(Long campusId) {
        return PREFIX + "coupons:" + campusId;
    }

    /** 专区概览聚合缓存（String/JSON，TTL 60s） */
    public static String overview(Long campusId) {
        return PREFIX + "overview:" + campusId;
    }

    /** 当前秒杀场次缓存（String/JSON，TTL 30s） */
    public static String flash(Long campusId) {
        return PREFIX + "flash:" + campusId;
    }

    /** 券剩余库存（String/int，Lua 原子扣减），TTL 到 end_at+1h */
    public static String couponStock(Long couponId) {
        return PREFIX + "coupon:stock:" + couponId;
    }

    /** 券已领张数（Hash：userId → 已领张数），限领判定用 */
    public static String couponUsers(Long couponId) {
        return PREFIX + "coupon:users:" + couponId;
    }

    /** 秒杀剩余库存（String/int，Lua 原子扣减），TTL 到 end_at+1h */
    public static String seckillStock(Long itemId) {
        return PREFIX + "seckill:stock:" + itemId;
    }

    /** 秒杀已购数（Hash：userId → 已购数），限购判定用 */
    public static String seckillUsers(Long itemId) {
        return PREFIX + "seckill:users:" + itemId;
    }

    /** 抢券限流（String，INCR + EXPIRE 5s，>1 拒绝） */
    public static String rateClaim(Long userId) {
        return PREFIX + "rate:claim:" + userId;
    }

    /** 抢购限流（String，INCR + EXPIRE 5s，>1 拒绝） */
    public static String rateGrab(Long userId) {
        return PREFIX + "rate:grab:" + userId;
    }

    /** 预热分布式锁（SET NX EX 30），防多实例重复预热 */
    public static String warmupLock() {
        return PREFIX + "lock:warmup";
    }
}
