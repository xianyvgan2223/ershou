package com.ershou.campus.module.map.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.cache.CacheProperties;
import com.ershou.campus.common.cache.DictionaryCache;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.map.dto.PointSuggestDTO;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import com.ershou.campus.module.map.vo.MapDataVO;
import com.ershou.campus.module.map.vo.TradePointVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradePointService {

    private static final TypeReference<List<TradePointVO>> POINT_LIST = new TypeReference<>() {
    };

    private final TradePointMapper pointMapper;
    private final ItemMapper itemMapper;
    private final ObjectMapper json;
    private final JsonCache cache;
    private final CacheEvictor evictor;
    private final DictionaryCache dict;
    private final CacheProperties cacheProps;

    public TradePoint getById(Long id) {
        return dict.point(id);
    }

    public List<TradePoint> listByCampus(Long campusId) {
        return pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                .eq(TradePoint::getCampusId, campusId)
                .eq(TradePoint::getStatus, 1)
                .eq(TradePoint::getAuditStatus, 1)
                .orderByDesc(TradePoint::getSafetyScore));
    }

    /** 地图页数据：底图配置 + 点位 + 每个点位的在售商品数/最低价 */
    public MapDataVO mapData(Long campusId) {
        var campus = dict.campus(campusId);
        if (campus == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "校区不存在");
        }
        MapDataVO vo = new MapDataVO();
        vo.setMapType(campus.getMapType());
        if (StringUtils.hasText(campus.getMapConfig())) {
            try {
                vo.setMapConfig(json.readValue(campus.getMapConfig(), Object.class));
            } catch (Exception e) {
                vo.setMapConfig(null);
            }
        }
        vo.setPoints(cachedPoints(campusId));
        return vo;
    }

    public List<TradePointVO> list(Long campusId) {
        return cachedPoints(campusId);
    }

    /**
     * 点位 + 在售数 / 最低价：整校区的在售商品聚合，原本每次请求都要把全校区在售商品捞进内存。
     * 缓存 key 带版本号（{@link CacheEvictor#mapVersion()}），商品写路径自增版本号即整体失效。
     */
    private List<TradePointVO> cachedPoints(Long campusId) {
        String key = RedisKeys.mapPoints(campusId, evictor.mapVersion());
        return cache.getOrLoad(key, cacheProps.getMapPointsTtl(), POINT_LIST,
                () -> withItemCount(listByCampus(campusId), campusId));
    }

    /**
     * 面交点推荐：距离为主、安全分与热度为辅。
     * 说明：MVP 用像素坐标做距离近似（自绘底图），22:00 后只推安全分 >= 85 的点位。
     */
    public List<TradePointVO> recommend(Long campusId, Long itemId, Long buyerId) {
        List<TradePointVO> all = cachedPoints(campusId);
        if (all.isEmpty()) {
            return List.of();
        }
        boolean night = LocalTime.now().isAfter(LocalTime.of(22, 0)) || LocalTime.now().isBefore(LocalTime.of(6, 0));
        if (night) {
            List<TradePointVO> safe = all.stream().filter(p -> p.getSafetyScore() != null && p.getSafetyScore() >= 85).toList();
            if (!safe.isEmpty()) {
                all = safe;
            }
        }
        BigDecimal refX = null;
        BigDecimal refY = null;
        if (itemId != null) {
            Item item = itemMapper.selectById(itemId);
            if (item != null && item.getPreferPointId() != null) {
                TradePoint p = dict.point(item.getPreferPointId());
                if (p != null) {
                    refX = p.getCoordX();
                    refY = p.getCoordY();
                }
            }
        }
        final BigDecimal fx = refX;
        final BigDecimal fy = refY;
        return all.stream()
                .sorted(Comparator.comparingDouble((TradePointVO p) -> score(p, fx, fy)).reversed())
                .limit(5)
                .toList();
    }

    private double score(TradePointVO p, BigDecimal refX, BigDecimal refY) {
        double distanceScore = 0.7;
        if (refX != null && p.getCoordX() != null) {
            double d = Math.hypot(p.getCoordX().doubleValue() - refX.doubleValue(),
                    p.getCoordY().doubleValue() - refY.doubleValue());
            distanceScore = Math.max(0, 1 - d / 800.0);
        }
        double safety = p.getSafetyScore() == null ? 0.6 : p.getSafetyScore() / 100.0;
        double hot = Math.min((p.getItemCount() == null ? 0 : p.getItemCount()) / 20.0, 1.0);
        return 0.5 * distanceScore + 0.3 * safety + 0.2 * hot;
    }

    public void suggest(PointSuggestDTO dto, Long userId) {
        TradePoint p = new TradePoint();
        p.setCampusId(dto.getCampusId());
        p.setName(dto.getName().trim());
        p.setSource(2);
        p.setPointType(dto.getPointType() == null ? 9 : dto.getPointType());
        p.setCoordX(dto.getCoordX());
        p.setCoordY(dto.getCoordY());
        p.setGuideText(dto.getGuideText());
        p.setSafetyScore(40);
        p.setSafeTags("[]");
        p.setStatus(1);
        p.setAuditStatus(0);
        p.setDealCount(0);
        p.setDisputeCount(0);
        p.setCreatorId(userId);
        pointMapper.insert(p);
        log.info("[map][suggest][{}][{}] 用户提交点位待审", p.getId(), userId);
    }

    private List<TradePointVO> withItemCount(List<TradePoint> points, Long campusId) {
        if (points.isEmpty()) {
            return List.of();
        }
        List<Item> items = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .eq(Item::getCampusId, campusId)
                .eq(Item::getStatus, 2)
                .isNotNull(Item::getPreferPointId)
                .select(Item::getId, Item::getPreferPointId, Item::getPrice));
        Map<Long, List<Item>> grouped = items.stream().collect(Collectors.groupingBy(Item::getPreferPointId));
        return points.stream().map(p -> {
            TradePointVO vo = new TradePointVO();
            vo.setId(p.getId());
            vo.setName(p.getName());
            vo.setType(p.getPointType());
            vo.setCoordX(p.getCoordX());
            vo.setCoordY(p.getCoordY());
            vo.setLng(p.getLng());
            vo.setLat(p.getLat());
            vo.setSafetyScore(p.getSafetyScore());
            vo.setGuideText(p.getGuideText());
            vo.setSafeTags(readList(p.getSafeTags()));
            vo.setAvailablePeriod(readList(p.getAvailablePeriod()));
            List<Item> list = grouped.getOrDefault(p.getId(), List.of());
            vo.setItemCount(list.size());
            vo.setMinPrice(list.stream().map(Item::getPrice).filter(Objects::nonNull)
                    .min(BigDecimal::compareTo).orElse(null));
            return vo;
        }).sorted(Comparator.comparing(TradePointVO::getItemCount).reversed()).toList();
    }

    private List<String> readList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 批量取点位：返回 HashMap，调用方即使拿 null key 取值也不会 NPE */
    public Map<Long, TradePoint> mapByIds(Collection<Long> ids) {
        Map<Long, TradePoint> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        for (TradePoint point : pointMapper.selectBatchIds(ids)) {
            map.putIfAbsent(point.getId(), point);
        }
        return map;
    }
}
