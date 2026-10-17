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
 *   <li><b>回源抢轻量重建锁</b>，抢不到的在锁存续期间轮询等待——防击穿（见 getOrLoad 的说明）；</li>
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

    /** 与 {@link #REBUILD_LOCK_TTL} 对应的秒数，只用于注释里引用（改一处即可） */
    private static final int REBUILD_LOCK_TTL_SECONDS = 5;

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
     *
     * <p><b>锁的语义（2026-10-10 修正，之前这里是错的）</b>：
     * 拿到锁的请求**在持锁期间完成回源**；没拿到的请求在
     * {@link CacheProperties#getMaxRebuildWaitMillis()} 上限内**轮询**等待，读到就返回。
     *
     * <p>旧实现是"抢不到锁 → 睡一次(80ms) → 再读一次 → 读不到就自己也去回源"，
     * 而 `loader` 又在锁外执行。结果：**只要回源比那一次等待慢，
     * 所有等待者都会各自回源一遍** —— 锁只削了并发峰值，根本没拦住击穿
     * （实测 50 并发打一个刚失效的热点 key，产生了约 50 次回源查询、
     * 每个请求都走了慢路径、P95 ≈ 500ms）。
     *
     * <p>为什么现在敢让等待方一直等：锁有 {@value #REBUILD_LOCK_TTL_SECONDS} 秒 TTL，
     * 重建方即使进程被杀，锁也会自己过期；再加上总等待上限，不会把请求挂死。
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
            // 别人正在回源：轮询等待，而不是"等一次就去打库"
            long spent = 0;
            long step = Math.max(props.getRebuildWaitMillis(), 10);
            long limit = Math.max(props.getMaxRebuildWaitMillis(), step);
            while (spent < limit) {
                sleepQuietly(step);
                spent += step;
                Hit<T> again = get(key, type);
                if (again.hit()) {
                    return again.value();
                }
                // 锁没了却还是没数据 → 重建方失败了（或没写缓存），这时才自己上
                if (!isRebuildLocked(key)) {
                    break;
                }
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
                unlock(key);
            }
        }
    }

    /** 重建锁是否还被别人持有（等待方用来判断"该继续等"还是"该自己上"） */
    private boolean isRebuildLocked(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key + ":rebuild"));
        } catch (Exception e) {
            log.warn("[cache][lock] 查询锁状态失败，按未加锁处理 key={}：{}", key, e.getMessage());
            return false;
        }
    }

    private void unlock(String key) {
        try {
            redis.delete(key + ":rebuild");
        } catch (Exception ignored) {
            // 锁会自己过期，删不掉不影响正确性
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
