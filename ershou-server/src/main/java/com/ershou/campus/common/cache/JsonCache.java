package com.ershou.campus.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 统一 JSON 读缓存（开发文档 4.7 缓存使用规约的落地实现）。
 *
 * <p>只用 StringRedisTemplate + 项目里那个 ObjectMapper 存 JSON：不引入第二套序列化，
 * 值在 redis-cli 里直接可读，语义与接口返回的 JSON 完全一致。
 *
 * <p>四条约定：
 * <ol>
 *   <li><b>空值也缓存</b>（哨兵 {@value #NULL_SENTINEL}）——防穿透；</li>
 *   <li><b>TTL 加 0~10% 随机抖动</b>——防雪崩（开发文档 6.3）；</li>
 *   <li><b>回源抢轻量重建锁</b>，抢不到的请求稍等再读一次——防击穿；</li>
 *   <li><b>Redis 异常一律降级为"未命中"</b>，由调用方回源 DB——缓存不参与正确性。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonCache {

    /** 空值哨兵：业务缓存都是对象/数组（以 { 或 [ 开头），不会和它混淆 */
    public static final String NULL_SENTINEL = "null";

    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CacheProperties props;

    /** 读结果：hit=false 未命中；hit=true 且 value=null 表示命中了"空值缓存" */
    public record Hit<T>(boolean hit, T value) {

        public static <T> Hit<T> miss() {
            return new Hit<>(false, null);
        }

        public static <T> Hit<T> of(T value) {
            return new Hit<>(true, value);
        }
    }

    public <T> Hit<T> get(String key, TypeReference<T> type) {
        if (!props.isEnabled()) {
            return Hit.miss();
        }
        try {
            return parse(redis.opsForValue().get(key), type);
        } catch (Exception e) {
            log.warn("[cache][get] 读取失败，降级回源 key={}：{}", key, e.getMessage());
            return Hit.miss();
        }
    }

    /** 批量读（MGET）：字典类数据一次取回，用于消掉列表页的 N+1 */
    public <T> List<Hit<T>> multiGet(List<String> keys, TypeReference<T> type) {
        List<Hit<T>> result = new ArrayList<>(keys.size());
        if (keys.isEmpty()) {
            return result;
        }
        if (!props.isEnabled()) {
            keys.forEach(k -> result.add(Hit.miss()));
            return result;
        }
        try {
            List<String> raws = redis.opsForValue().multiGet(keys);
            for (int i = 0; i < keys.size(); i++) {
                result.add(parse(raws == null ? null : raws.get(i), type));
            }
        } catch (Exception e) {
            log.warn("[cache][mget] 读取失败，降级回源 keys={}：{}", keys.size(), e.getMessage());
            result.clear();
            keys.forEach(k -> result.add(Hit.miss()));
        }
        return result;
    }

    public void set(String key, Object value, Duration ttl) {
        if (!props.isEnabled() || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), jitter(ttl));
        } catch (Exception e) {
            log.warn("[cache][set] 写入失败，忽略 key={}：{}", key, e.getMessage());
        }
    }

    /** 是否命中空值缓存（防穿透）：商品、点位这类按 id 直查的接口回源前先问一次 */
    public boolean isNullCached(String key) {
        if (!props.isEnabled()) {
            return false;
        }
        try {
            return NULL_SENTINEL.equals(redis.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("[cache][isNull] 读取失败，按未命中处理 key={}：{}", key, e.getMessage());
            return false;
        }
    }

    /** 写空值缓存：库里也没有这条数据时调用，避免同一个不存在的 id 被反复穿透 */
    public void setNull(String key, Duration ttl) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key, NULL_SENTINEL, jitter(ttl));
        } catch (Exception e) {
            log.warn("[cache][setNull] 写入失败，忽略 key={}：{}", key, e.getMessage());
        }
    }

    /**
     * 读缓存 → 未命中回源 → 回填缓存。
     * 回源异常（业务异常、DB 异常）原样抛出，不会被缓存吞掉。
     */
    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        Hit<T> hit = get(key, type);
        if (hit.hit()) {
            return hit.value();
        }
        if (!props.isEnabled()) {
            return loader.get();
        }
        boolean locked = tryLock(key);
        if (!locked) {
            // 热点 key 刚失效：可能已经有请求在回源，稍等再读一次，读不到就自己去 DB
            sleepQuietly(props.getRebuildWaitMillis());
            Hit<T> again = get(key, type);
            if (again.hit()) {
                return again.value();
            }
        }
        try {
            T value = loader.get();
            if (value == null) {
                setNull(key, props.getNullValueTtl());
            } else {
                set(key, value, ttl);
            }
            return value;
        } finally {
            if (locked) {
                try {
                    redis.delete(key + ":rebuild");
                } catch (Exception ignored) {
                    // 锁会自己过期，删不掉不影响正确性
                }
            }
        }
    }

    private <T> Hit<T> parse(String raw, TypeReference<T> type) throws Exception {
        if (raw == null) {
            return Hit.miss();
        }
        if (NULL_SENTINEL.equals(raw)) {
            return Hit.of(null);
        }
        return Hit.of(json.readValue(raw, type));
    }

    private boolean tryLock(String key) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":rebuild", "1", REBUILD_LOCK_TTL));
        } catch (Exception e) {
            log.warn("[cache][lock] 加锁失败，直接回源 key={}：{}", key, e.getMessage());
            return false;
        }
    }

    /** TTL 抖动 ±10%：避免同一批 key 同时过期把 DB 打穿 */
    private Duration jitter(Duration ttl) {
        long base = Math.max(ttl.toMillis(), 1L);
        long extra = ThreadLocalRandom.current().nextLong(base / 10 + 1);
        return ttl.plusMillis(extra);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
