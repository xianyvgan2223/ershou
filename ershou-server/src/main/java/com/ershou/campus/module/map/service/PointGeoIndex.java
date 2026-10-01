package com.ershou.campus.module.map.service;

import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * 点位的地理位置索引（Redis GEO），用来做"附近点位"检索。
 *
 * <h3>为什么值得单独做一层</h3>
 * 高德「周边搜索」属于基础搜索服务，个人认证只有 <b>5000 次/月</b>；
 * 而"我附近有哪些面交点"是本平台最自然的入口之一（学生只关心自己宿舍/教学楼附近）。
 * 把点位坐标索引到 Redis GEO 之后，这个功能<b>一次高德调用都不需要</b>：
 * 坐标来自用户浏览器的定位（`navigator.geolocation`，也是 0 配额），
 * 检索走 Redis 的 `GEOSEARCH`。
 *
 * <h3>为什么不在 MySQL 里算</h3>
 * 用经纬度做"半径内排序取前 N"在 MySQL 里要么退化成全表扫描 + Haversine 计算，
 * 要么依赖空间索引 + 自定义函数。一个校区的点位数量不大但查询频繁，
 * 交给 Redis 的 GEO（底层是 zset + geohash，按距离排序是原生的）更合适，
 * 也和项目已有的 {@code ershou:map:points:*} 缓存策略一致。
 *
 * <h3>一致性怎么保证</h3>
 * 沿用地图缓存的<b>版本号整体失效</b>方案：key 里带 {@code v{version}}，
 * 写点位时 {@link CacheEvictor#pointChanged} 会把版本号 +1，旧索引立刻不可达，
 * 下次查询按 DB 重建。所以这里<b>不需要</b>在增删点位时精细维护成员，
 * 也不用担心"删了一半"的中间状态被读到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointGeoIndex {

    private final StringRedisTemplate redis;
    private final TradePointMapper pointMapper;
    private final CacheEvictor evictor;

    /** 索引 TTL：比地图缓存长得多（重建要读全表，成本高；数据本身很少变） */
    private static final Duration INDEX_TTL = Duration.ofHours(6);
    /** 重建锁 TTL：防止并发查询同时全量重建 */
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(30);

    /**
     * 在半径内找点位，按距离从近到远。
     *
     * @param campusId 校区
     * @param lng      用户位置经度（**必须是 GCJ-02**，浏览器 GPS 的 WGS-84 要前端转换）
     * @param lat      用户位置纬度
     * @param radius   半径（米）
     * @param limit    最多返回多少个
     * @return pointId → 距离（米），保持由近到远的顺序
     */
    public Map<Long, Double> nearby(Long campusId, double lng, double lat, int radius, int limit) {
        String key = currentKey(campusId);
        if (key == null) {
            return Map.of();
        }
        try {
            // GEOSEARCH key FROMMEMBER/FROMLONLAT ... BYRADIUS r m ASC COUNT n WITHDIST
            // 用 GeoReference 重载（Circle 那几个重载在 StringRedisTemplate 的泛型下解析歧义）
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
                    // member 不是数字：理论上不会发生，跳过即可
                }
            });
            return ordered;
        } catch (Exception e) {
            // GEO 检索失败不能让接口挂掉：返回空，由上层决定是否回退到普通列表
            log.warn("[geo][nearby] 检索失败 campus={} radius={}：{}", campusId, radius, e.getMessage());
            return Map.of();
        }
    }

    /** 当前版本号对应的索引 key；不存在（或为空）时按 DB 重建一次 */
    private String currentKey(Long campusId) {
        long version = evictor.mapVersion();
        String key = RedisKeys.mapGeo(campusId, version);
        try {
            Boolean exists = redis.hasKey(key);
            if (Boolean.TRUE.equals(exists)) {
                return key;
            }
        } catch (Exception e) {
            log.warn("[geo][exists] 检查索引失败：{}", e.getMessage());
            return null;
        }
        return rebuild(campusId, key) ? key : null;
    }

    /**
     * 按 DB 重建索引。加一把短锁：并发查询只让一个实例去重建，其余直接放弃本次检索
     * （下次请求时索引已就绪）。避免热点时刻 N 个请求同时读全表。
     */
    private boolean rebuild(Long campusId, String key) {
        String lockKey = RedisKeys.mapGeoRebuildLock(campusId);
        Boolean locked;
        try {
            locked = redis.opsForValue().setIfAbsent(lockKey, "1", REBUILD_LOCK_TTL);
        } catch (Exception e) {
            log.warn("[geo][rebuild] 抢锁失败：{}", e.getMessage());
            return false;
        }
        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }
        try {
            List<TradePoint> points = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                    .select(TradePoint::getId, TradePoint::getLng, TradePoint::getLat)
                    .eq(TradePoint::getCampusId, campusId)
                    .eq(TradePoint::getStatus, 1)
                    .isNotNull(TradePoint::getLng)
                    .isNotNull(TradePoint::getLat));
            if (points.isEmpty()) {
                // 没有可索引的点位：写个占位成员，避免每次查询都重建（成员名不可能撞上数字 id）
                redis.opsForGeo().add(key, new Point(0, 0), "__empty__");
            } else {
                Map<String, Point> batch = new LinkedHashMap<>();
                for (TradePoint p : points) {
                    batch.put(String.valueOf(p.getId()),
                            new Point(p.getLng().doubleValue(), p.getLat().doubleValue()));
                }
                redis.opsForGeo().add(key, batch);
            }
            redis.expire(key, INDEX_TTL);
            log.info("[geo][rebuild] 校区 {} 索引重建完成，点位数 {}", campusId, points.size());
            return true;
        } catch (Exception e) {
            log.warn("[geo][rebuild] 重建失败 campus={}：{}", campusId, e.getMessage());
            return false;
        } finally {
            try {
                redis.delete(lockKey);
            } catch (Exception ignored) {
                // 删不掉也没事，锁有 30 秒 TTL
            }
        }
    }
}
