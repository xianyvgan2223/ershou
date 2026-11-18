package com.ershou.cloud.platform.cache;

import com.ershou.cloud.common.cache.CacheEvictChannel;
import com.ershou.cloud.common.cache.RawLocalCache;
import com.ershou.cloud.platform.config.PlatformCacheProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 平台服务专属 JSON 读缓存（替代单体的 {@code common.cache.JsonCache}）。
 *
 * <p>云拆分后 platform-service 不再依赖单体 common，缓存约定保持一致：
 * <ol>
 *   <li>空值也缓存（哨兵 {@code null}）防穿透；</li>
 *   <li>TTL 加 0~10% 随机抖动防雪崩；</li>
 *   <li>Redis 异常一律降级为「未命中」，由调用方回源 DB（缓存不参与正确性）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformJsonCache {

    public static final String NULL_SENTINEL = "null";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final PlatformCacheProperties cacheProps;

    /** L2 进程内缓存：只存 raw JSON，每次反序列化成新对象 */
    private RawLocalCache local;

    @PostConstruct
    void initLocal() {
        if (cacheProps.isLocalEnabled()) {
            local = new RawLocalCache(cacheProps.getLocalMaxBytes(), cacheProps.getLocalTtl());
        }
    }

    /** 读缓存：hit=true 且 value=null 表示命中空值缓存 */
    public record Hit<T>(boolean hit, T value) {
        public static <T> Hit<T> miss() {
            return new Hit<>(false, null);
        }

        public static <T> Hit<T> of(T value) {
            return new Hit<>(true, value);
        }
    }

    public <T> Hit<T> get(String key, TypeReference<T> type) {
        try {
            String raw = localGet(key);
            if (raw == null) {
                raw = redis.opsForValue().get(key);
                if (raw != null) {
                    localPut(key, raw);
                }
            }
            if (raw == null) {
                return Hit.miss();
            }
            if (NULL_SENTINEL.equals(raw)) {
                return Hit.of(null);
            }
            return Hit.of(json.readValue(raw, type));
        } catch (Exception e) {
            log.warn("[platform][cache][get] 读取失败降级回源 key={}：{}", key, e.getMessage());
            return Hit.miss();
        }
    }

    /**
     * 读穿透：命中直接返回；未命中执行 loader 并回填（失败不影响主流程）。
     */
    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        Hit<T> hit = get(key, type);
        if (hit.hit()) {
            return hit.value();
        }
        T value = loader.get();
        if (value == null) {
            setNull(key, ttl);
        } else {
            set(key, value, ttl);
        }
        return value;
    }

    public void set(String key, Object value, Duration ttl) {
        if (value == null) {
            setNull(key, ttl);
            return;
        }
        try {
            String raw = json.writeValueAsString(value);
            localPut(key, raw);
            redis.opsForValue().set(key, raw, jitter(ttl));
        } catch (Exception e) {
            log.warn("[platform][cache][set] 写入失败忽略 key={}：{}", key, e.getMessage());
        }
    }

    public void setNull(String key, Duration ttl) {
        try {
            localPut(key, NULL_SENTINEL);
            redis.opsForValue().set(key, NULL_SENTINEL, jitter(ttl));
        } catch (Exception e) {
            log.warn("[platform][cache][setNull] 写入失败忽略 key={}：{}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        delete(List.of(key));
    }

    /** 删 Redis + 清本地 + 广播给同服务其它实例 */
    public void delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (cacheProps.isLocalEnabled() && local != null) {
            local.invalidate(keys);
        }
        try {
            redis.delete(keys);
        } catch (Exception e) {
            log.warn("[platform][cache][del] 删除失败忽略 keys={}：{}", keys.size(), e.getMessage());
        }
        if (cacheProps.isPubsubEnabled()) {
            for (String key : keys) {
                try {
                    redis.convertAndSend(CacheEvictChannel.NAME,
                            CacheEvictChannel.message(CacheEvictChannel.SCOPE_PLATFORM, key));
                } catch (Exception e) {
                    log.warn("[platform][cache][pubsub] 失效广播失败 key={}：{}", key, e.getMessage());
                }
            }
        }
    }

    /** 只清本地（Pub/Sub 订阅方使用，避免再次广播造成回环） */
    public void deleteLocalOnly(Collection<String> keys) {
        if (cacheProps.isLocalEnabled() && local != null) {
            local.invalidate(keys);
        }
    }

    public void clearLocal() {
        if (local != null) {
            local.invalidateAll();
        }
    }

    private String localGet(String key) {
        if (cacheProps.isLocalEnabled() && local != null) {
            return local.get(key);
        }
        return null;
    }

    private void localPut(String key, String raw) {
        if (cacheProps.isLocalEnabled() && local != null) {
            local.put(key, raw);
        }
    }

    /** TTL 抖动：+0~10%，避免大量 key 同时过期。 */
    private Duration jitter(Duration ttl) {
        long base = ttl.toMillis();
        long delta = base <= 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(base / 10, 1));
        return Duration.ofMillis(base + delta);
    }
}
