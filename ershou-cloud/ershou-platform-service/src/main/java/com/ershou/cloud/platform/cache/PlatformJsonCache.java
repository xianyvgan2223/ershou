package com.ershou.cloud.platform.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
            String raw = redis.opsForValue().get(key);
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
            redis.opsForValue().set(key, json.writeValueAsString(value), jitter(ttl));
        } catch (Exception e) {
            log.warn("[platform][cache][set] 写入失败忽略 key={}：{}", key, e.getMessage());
        }
    }

    public void setNull(String key, Duration ttl) {
        try {
            redis.opsForValue().set(key, NULL_SENTINEL, jitter(ttl));
        } catch (Exception e) {
            log.warn("[platform][cache][setNull] 写入失败忽略 key={}：{}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[platform][cache][del] 删除失败忽略 key={}：{}", key, e.getMessage());
        }
    }

    /** TTL 抖动：+0~10%，避免大量 key 同时过期。 */
    private Duration jitter(Duration ttl) {
        long base = ttl.toMillis();
        long delta = base <= 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(base / 10, 1));
        return Duration.ofMillis(base + delta);
    }
}
