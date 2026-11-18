package com.ershou.cloud.map.service;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.map.config.MapCacheKeys;
import com.ershou.cloud.map.config.MapProperties;
import com.ershou.cloud.map.feign.ItemClient;
import com.ershou.cloud.map.feign.PointItemStatVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点位商品统计（跨服务只读：item-service 的 {@code /inner/items/count-by-point}）。
 *
 * <p>两套读取口径：
 * <ul>
 *   <li>{@link #statsByCampus}：带短缓存（默认 10s），给高频地图/列表接口用；下游失败返回**空 Map**（降级显示 0 件）；</li>
 *   <li>{@link #loadFreshOrNull}：不走缓存，下游失败返回 **null**（供空点位清理判断——拿不到统计时绝不能删点位）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemStatService {

    private static final TypeReference<Map<Long, PointItemStatVO>> STATS_TYPE =
            new TypeReference<Map<Long, PointItemStatVO>>() {
            };

    private final ItemClient itemClient;
    private final MapCacheService cache;
    private final MapProperties props;

    /** 校区点位商品统计：pointId → stat；item-service 不可用时返回空 Map（地图降解为 0 件，不报错） */
    public Map<Long, PointItemStatVO> statsByCampus(Long campusId) {
        String key = MapCacheKeys.itemStats(campusId);
        Map<Long, PointItemStatVO> stats = cache.getOrLoad(
                key, Duration.ofSeconds(props.getItemStatsCacheSeconds()), STATS_TYPE,
                () -> {
                    Map<Long, PointItemStatVO> loaded = loadFreshOrNull(campusId);
                    return loaded == null ? new LinkedHashMap<>() : loaded;
                });
        return stats == null ? new LinkedHashMap<>() : stats;
    }

    /** 不走缓存的实时统计；下游失败/降级时返回 null（调用方必须区别对待"空统计"与"取不到"） */
    public Map<Long, PointItemStatVO> loadFreshOrNull(Long campusId) {
        Map<Long, PointItemStatVO> map = new LinkedHashMap<>();
        try {
            Result<List<PointItemStatVO>> result = itemClient.countByPoint(campusId);
            if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
                log.warn("[map][item-stats][{}] 取点位商品数失败（降级）：code={} msg={}",
                        campusId, result == null ? "null" : result.getCode(),
                        result == null ? "" : result.getMessage());
                return null;
            }
            if (result.getData() != null) {
                for (PointItemStatVO row : result.getData()) {
                    if (row != null && row.getPointId() != null) {
                        map.put(row.getPointId(), row);
                    }
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("[map][item-stats][{}] 调用 item-service 异常（降级）：{}", campusId, e.getMessage());
            return null;
        }
    }

    /** 某个点位的商品列表：透传 item-service 的 {@code /inner/items/by-point}（分页结构原样返回） */
    public Result<Object> itemsByPoint(Long campusId, Long pointId, long page, long pageSize) {
        try {
            Result<Object> result = itemClient.byPoint(campusId, pointId, page, pageSize);
            if (result == null) {
                return Result.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "商品服务暂不可用");
            }
            return result;
        } catch (Exception e) {
            log.warn("[map][items-by-point][{}][{}] 调用 item-service 异常：{}", campusId, pointId, e.getMessage());
            return Result.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "商品服务暂不可用");
        }
    }
}
