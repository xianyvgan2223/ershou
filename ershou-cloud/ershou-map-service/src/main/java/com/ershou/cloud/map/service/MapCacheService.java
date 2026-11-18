package com.ershou.cloud.map.service;

import com.ershou.cloud.map.config.MapCacheKeys;
import com.ershou.cloud.map.config.MapProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    }

    public <T> T getOrLoad(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        if (props.isCacheEnabled()) {
            try {
                String raw = redis.opsForValue().get(key);
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
                redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
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
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[map][cache] 删除 {} 失败：{}", key, e.getMessage());
        }
    }
}
