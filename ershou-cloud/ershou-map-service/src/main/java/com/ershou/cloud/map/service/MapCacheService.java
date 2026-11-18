package com.ershou.cloud.map.service;

import com.ershou.cloud.common.cache.CacheEvictChannel;
import com.ershou.cloud.common.cache.RawLocalCache;
import com.ershou.cloud.map.config.MapCacheKeys;
import com.ershou.cloud.map.config.MapProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 地图服务本地缓存（Redis db5，JSON 字符串；语义对齐单体的 JsonCache + CacheEvictor.mapVersion）。
 *
 * <p>两类缓存分开：
 * <ul>
 *   <li><b>点位基础列表</b>：版本号整体失效（写点位 INCR {@code ershou:map:points:ver}），TTL 短（默认 60s）；</li>
 *   <li><b>点位商品统计</b>：来自 item-service Feign，独立短 TTL（默认 10s）——item 侧写商品不会回调本服务，
 *       只能靠短 TTL 保证"下单/下架后地图数字"尽快一致（遗留项，见报告）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final MapProperties props;

    /** L2 进程内缓存：只存 raw JSON */
    private RawLocalCache local;

    @PostConstruct
    void initLocal() {
        if (props.isCacheEnabled() && props.isLocalCacheEnabled()) {
            local = new RawLocalCache(props.getLocalMaxBytes(), Duration.ofSeconds(props.getLocalCacheSeconds()));
        }
    }

    /** 当前点位缓存版本号；Redis 不可用/异常时按 0 处理（退化为直连 DB，不影响可用性） */
    public long version() {
        try {
            String raw = redis.opsForValue().get(MapCacheKeys.pointsVersion());
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (Exception e) {
            log.warn("[map][cache][ver] 读取版本号失败，按 0 处理：{}", e.getMessage());
            return 0L;
        }
    }

    /** 写点位后自增版本号，让所有 {@code v{version}} 的缓存/索引立即失效 */
    public void bumpVersion() {
        try {
            redis.opsForValue().increment(MapCacheKeys.pointsVersion());
        } catch (Exception e) {
            log.warn("[map][cache][ver] 版本号自增失败（新点位可能延迟 {}s 可见）：{}", props.getCacheSeconds(), e.getMessage());
        }
        // 本地 key 带版本号，旧条目已不可达；整体清掉释放内存，并通知其它实例
        invalidateLocalAll();
        publishEvict(CacheEvictChannel.ALL_KEYS);
    }

    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        if (props.isCacheEnabled()) {
            try {
                String raw = localGet(key);
                if (raw == null) {
                    raw = redis.opsForValue().get(key);
                    if (StringUtils.hasText(raw)) {
                        localPut(key, raw);
                    }
                }
                if (StringUtils.hasText(raw)) {
                    return json.readValue(raw, type);
                }
            } catch (Exception e) {
                log.warn("[map][cache] 读取 {} 失败，回源：{}", key, e.getMessage());
            }
        }
        T value = loader.get();
        if (props.isCacheEnabled() && value != null) {
            try {
                String raw = json.writeValueAsString(value);
                localPut(key, raw);
                redis.opsForValue().set(key, raw, ttl);
            } catch (Exception e) {
                log.warn("[map][cache] 写入 {} 失败（不影响本次请求）：{}", key, e.getMessage());
            }
        }
        return value;
    }

    public void delete(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        invalidateLocal(key);
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[map][cache] 删除 {} 失败：{}", key, e.getMessage());
        }
        publishEvict(key);
    }

    /** 点位版本变化：本地 key 带版本号，直接整体清空最省事，并通知其它实例 */
    public void invalidateLocalAll() {
        if (local != null) {
            local.invalidateAll();
        }
    }

    public void invalidateLocal(String key) {
        if (props.isLocalCacheEnabled() && local != null) {
            local.invalidate(java.util.List.of(key));
        }
    }

    private String localGet(String key) {
        if (props.isLocalCacheEnabled() && local != null) {
            return local.get(key);
        }
        return null;
    }

    private void localPut(String key, String raw) {
        if (props.isLocalCacheEnabled() && local != null) {
            local.put(key, raw);
        }
    }

    private void publishEvict(String key) {
        if (!props.isPubsubEnabled()) {
            return;
        }
        try {
            redis.convertAndSend(CacheEvictChannel.NAME,
                    CacheEvictChannel.message(CacheEvictChannel.SCOPE_MAP, key));
        } catch (Exception e) {
            log.warn("[map][cache][pubsub] 失效广播失败 key={}：{}", key, e.getMessage());
        }
    }
}
