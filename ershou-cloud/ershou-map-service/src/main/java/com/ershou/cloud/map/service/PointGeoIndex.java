package com.ershou.cloud.map.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.map.config.MapCacheKeys;
import com.ershou.cloud.map.config.MapProperties;
import com.ershou.cloud.map.entity.TradePoint;
import com.ershou.cloud.map.mapper.TradePointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 点位地理位置索引（Redis GEO，db5），用于「我附近的点位」检索。
 *
 * <p>从单体 {@code module/map/service/PointGeoIndex} 迁移：**不消耗高德配额**，全部走 Redis GEOSEARCH。
 * 一致性沿用单体的「版本号整体失效」：key 带 {@code v{version}}，写点位后 bump 版本号，
 * 旧索引立即不可达，下次查询按 DB 全量重建；重建用 Redisson 短锁防并发重复建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointGeoIndex {

    private final StringRedisTemplate redis;
    private final TradePointMapper pointMapper;
    private final MapCacheService cache;
    private final MapProperties props;
    /** Redisson 分布式锁（GEO 索引重建锁） */
    private final RedissonClient redisson;

    /**
     * 半径内找点位，按距离从近到远。
     *
     * @param lng 用户位置经度（GCJ-02，前端已转换）
     * @param lat 用户位置纬度
     * @return pointId → 距离（米），保持由近到远顺序
     */
    public Map<Long, Double> nearby(Long campusId, double lng, double lat, int radius, int limit) {
        String key = currentKey(campusId);
        if (key == null) {
            return Map.of();
        }
        try {
            var results = redis.opsForGeo().search(
                    key,
                    GeoReference.fromCoordinate(lng, lat),
                    new Distance(radius, Metrics.NEUTRAL),
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                            .includeDistance()
                            .sortAscending()
                            .limit(limit));
            Map<Long, Double> ordered = new LinkedHashMap<>();
            if (results == null) {
                return ordered;
            }
            results.forEach(result -> {
                try {
                    Long pointId = Long.valueOf(String.valueOf(result.getContent().getName()));
                    ordered.put(pointId, result.getDistance().getValue());
                } catch (NumberFormatException ignored) {
                    // member 不是数字（例如空索引占位符 __empty__）：跳过
                }
            });
            return ordered;
        } catch (Exception e) {
            // GEO 检索失败不能让接口挂掉：返回空，由上层降级
            log.warn("[geo][nearby] 检索失败 campus={} radius={}：{}", campusId, radius, e.getMessage());
            return Map.of();
        }
    }

    /** 当前版本号对应的索引 key；不存在（或为空）时按 DB 重建一次 */
    private String currentKey(Long campusId) {
        long version = cache.version();
        String key = MapCacheKeys.geoIndex(campusId, version);
        try {
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                return key;
            }
        } catch (Exception e) {
            log.warn("[geo][exists] 检查索引失败：{}", e.getMessage());
            return null;
        }
        return rebuild(campusId, key) ? key : null;
    }

    /** 按 DB 重建索引：Redisson 短锁防并发重复重建；只索引启用中且有经纬度的点位（像素坐标不是地理坐标） */
    private boolean rebuild(Long campusId, String key) {
        String lockKey = MapCacheKeys.geoRebuildLock(campusId) + ":redisson";
        RLock lock = redisson.getLock(lockKey);
        boolean locked;
        try {
            locked = lock.tryLock(0, Math.max(5, props.getGeoRebuildLockSeconds()), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("[geo][rebuild] 抢锁失败：{}", e.getMessage());
            return false;
        }
        if (!locked) {
            return false;
        }
        try {
            List<TradePoint> points = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                    .select(TradePoint::getId, TradePoint::getLng, TradePoint::getLat)
                    .eq(TradePoint::getCampusId, campusId)
                    .eq(TradePoint::getStatus, TradePointService.STATUS_ENABLED)
                    .isNotNull(TradePoint::getLng)
                    .isNotNull(TradePoint::getLat));
            if (points.isEmpty()) {
                // 没有可索引点位：写占位成员，避免每次查询都重建（成员名不可能撞上数字 id）
                redis.opsForGeo().add(key, new Point(0, 0), "__empty__");
            } else {
                Map<String, Point> batch = new LinkedHashMap<>();
                for (TradePoint p : points) {
                    batch.put(String.valueOf(p.getId()),
                            new Point(p.getLng().doubleValue(), p.getLat().doubleValue()));
                }
                redis.opsForGeo().add(key, batch);
            }
            redis.expire(key, Duration.ofHours(Math.max(1, props.getGeoIndexHours())));
            log.info("[geo][rebuild] 校区 {} 索引重建完成，点位数 {}", campusId, points.size());
            return true;
        } catch (Exception e) {
            log.warn("[geo][rebuild] 重建失败 campus={}：{}", campusId, e.getMessage());
            return false;
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception ignored) {
                // 锁有 leaseTime，进程异常时会自动释放
            }
        }
    }
}
