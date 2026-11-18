package com.ershou.cloud.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;

/**
 * 三级缓存的 L2：进程内 Caffeine，只存原始 JSON 字符串。
 *
 * <p>为什么不存对象：商品详情等读路径命中后会“就地修改”返回对象（叠加实时浏览量、favorited），
 * 如果本地缓存保存对象引用，第二个请求会读到被上一个请求改过的数据。存 raw JSON 后每次
 * 由调用方重新反序列化，和 Redis 缓存的语义完全一致。
 *
 * <p>容量按 UTF-8 字节数限制，避免大响应把堆撑爆；TTL 由各服务配置决定（默认 5s）。
 * 该类不注册为 Spring Bean：file/gateway 等服务不需要 L2，避免无差别引入内存缓存。
 */
public class RawLocalCache {

    private final Cache<String, String> cache;

    public RawLocalCache(long maxBytes, Duration ttl) {
        long weight = Math.max(maxBytes, 1024L);
        Duration expire = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofSeconds(5) : ttl;
        this.cache = Caffeine.newBuilder()
                .maximumWeight(weight)
                .weigher((String key, String value) -> key.getBytes(StandardCharsets.UTF_8).length
                        + value.getBytes(StandardCharsets.UTF_8).length)
                .expireAfterWrite(expire)
                .recordStats()
                .build();
    }

    public String get(String key) {
        return key == null ? null : cache.getIfPresent(key);
    }

    public void put(String key, String value) {
        if (key != null && value != null) {
            cache.put(key, value);
        }
    }

    public void invalidate(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            cache.invalidateAll(keys);
        }
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }

    public CacheStats stats() {
        return cache.stats();
    }
}
