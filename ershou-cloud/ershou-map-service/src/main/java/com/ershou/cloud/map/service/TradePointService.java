package com.ershou.cloud.map.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.map.config.MapCacheKeys;
import com.ershou.cloud.map.config.MapProperties;
import com.ershou.cloud.map.dto.PointSuggestDTO;
import com.ershou.cloud.map.entity.TradePoint;
import com.ershou.cloud.map.feign.CampusVO;
import com.ershou.cloud.map.feign.InnerItemVO;
import com.ershou.cloud.map.feign.ItemClient;
import com.ershou.cloud.map.feign.PointItemStatVO;
import com.ershou.cloud.map.mapper.TradePointMapper;
import com.ershou.cloud.map.vo.InnerPointVO;
import com.ershou.cloud.map.vo.MapDataVO;
import com.ershou.cloud.map.vo.TradePointVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 面交点/地图服务（从单体 {@code module/map/service/TradePointService} 全量迁移）。
 *
 * <p>拆分后的关键差异（**不跨库 SQL**）：
 * <ul>
 *   <li>点位商品数/最低价：单体直接查 item 表，这里改走 item-service Feign {@code /inner/items/count-by-point}；</li>
 *   <li>校区底图配置：改走 user-service Feign（{@link CampusConfigService}）；</li>
 *   <li>缓存：单体 {@code JsonCache + CacheEvictor.mapVersion}，这里用 {@link MapCacheService} + Redis db5；</li>
 *   <li>“按商品推荐点位”的参考坐标：单体查 item 表，这里 Feign item-service {@code /inner/items/{id}}。</li>
 * </ul>
 *
 * <p>对外 JSON 与单体保持一致（前端零改动）：{@code /map/points}、{@code /trade-points}、
 * {@code /trade-points/recommend}、{@code /trade-points/nearby}、{@code /trade-points/my-points} 等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradePointService {

    /** 商品的"还有戏"状态：草稿 / 审核中 / 在售 / 已锁定（与单体一致；cleanup/搜索落库可见性口径） */
    private static final List<Integer> ITEM_ACTIVE_STATUS = List.of(0, 1, 2, 3);

    /** 点位名称长度上限：与前端输入框 maxlength 一致（表 VARCHAR(64)，收紧避免地图标签过长） */
    private static final int NAME_MAX_LENGTH = 30;

    /** 点位来源：1=官方开通，2=用户提交，3=高德搜索落库 */
    public static final int SOURCE_OFFICIAL = 1;
    public static final int SOURCE_USER = 2;
    public static final int SOURCE_AMAP_SEARCH = 3;

    /** 点位状态：1=启用 0=停用 */
    public static final int STATUS_ENABLED = 1;

    private static final TypeReference<List<TradePoint>> POINT_LIST_TYPE = new TypeReference<>() {
    };

    private final TradePointMapper pointMapper;
    private final ItemStatService itemStats;
    private final CampusConfigService campusConfig;
    private final MapCacheService cache;
    private final PointGeoIndex geoIndex;
    private final ItemClient itemClient;
    private final ObjectMapper json;
    private final MapProperties props;

    // ---------------------------------------------------------------- 查询

    public TradePoint getById(Long id) {
        return id == null ? null : pointMapper.selectById(id);
    }

    /** 校区点位基础列表（本库缓存，版本号失效；不含商品统计） */
    public List<TradePoint> basePoints(Long campusId) {
        long version = cache.version();
        String key = MapCacheKeys.basePoints(campusId, version);
        List<TradePoint> list = cache.getOrLoad(key, java.time.Duration.ofSeconds(props.getCacheSeconds()),
                POINT_LIST_TYPE, () -> pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                        .eq(TradePoint::getCampusId, campusId)
                        .eq(TradePoint::getStatus, STATUS_ENABLED)
                        .orderByDesc(TradePoint::getSafetyScore)));
        return list == null ? List.of() : list;
    }

    public List<TradePoint> listByCampus(Long campusId) {
        return listByCampus(campusId, false);
    }

    /**
     * @param onlyWithItems 地图场景 true：没商品的点位一个都不画；发布页/下拉 false：保留 0 商品点位
     *                      （否则用户刚建的点位选不到）。搜索落库（source=3）无论开关都需挂了商品才出现。
     */
    public List<TradePoint> listByCampus(Long campusId, boolean onlyWithItems) {
        List<TradePoint> all = basePoints(campusId);
        Set<Long> usedPointIds = itemStats.statsByCampus(campusId).keySet();
        return all.stream().filter(p -> {
            boolean hasItems = usedPointIds.contains(p.getId());
            if (isSearchHit(p) && !hasItems) {
                return false;
            }
            return !onlyWithItems || hasItems;
        }).toList();
    }

    /** 地图页数据：底图配置 + 点位 + 每个点位的商品数/最低价（**不含 0 商品点位**） */
    public MapDataVO mapData(Long campusId) {
        CampusVO campus = campusConfig.campus(campusId);
        MapDataVO vo = new MapDataVO();
        vo.setMapType(campus == null || campus.getMapType() == null ? 1 : campus.getMapType());
        vo.setMapConfig(campusConfig.parseMapConfig(campus));
        vo.setPoints(withItemCount(listByCampus(campusId, true), campusId));
        return vo;
    }

    /** 点位下拉列表（发布页/私聊选点）：保留 0 商品点位 */
    public List<TradePointVO> list(Long campusId) {
        return withItemCount(listByCampus(campusId, false), campusId);
    }

    /**
     * 「我附近的点位」：Redis GEO 检索（不消耗高德配额），按距离从近到远。
     * 可见性规则与 {@link #listByCampus} 一致：搜索落库（source=3）的点位只有挂了商品才出现。
     */
    public List<Map<String, Object>> nearbyPoints(Long campusId, double lng, double lat,
                                                  Integer radius, Integer limit) {
        int r = radius == null ? props.getNearbyDefaultRadius()
                : Math.max(50, Math.min(radius, props.getNearbyMaxRadius()));
        int size = limit == null ? props.getNearbyDefaultLimit()
                : Math.max(1, Math.min(limit, props.getNearbyMaxLimit()));
        Map<Long, Double> ordered = geoIndex.nearby(campusId, lng, lat, r, size);
        if (ordered.isEmpty()) {
            return List.of();
        }
        Set<Long> visibleSearchHits = itemStats.statsByCampus(campusId).keySet();
        Map<Long, TradePoint> byId = mapByIds(ordered.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        ordered.forEach((pointId, distance) -> {
            TradePoint p = byId.get(pointId);
            if (p == null || p.getStatus() == null || p.getStatus() != STATUS_ENABLED) {
                return; // 已删除/停用：跳过，不报错
            }
            if (isSearchHit(p) && !visibleSearchHits.contains(p.getId())) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId());
            row.put("name", p.getName());
            row.put("pointType", p.getPointType());
            row.put("lng", p.getLng());
            row.put("lat", p.getLat());
            row.put("guideText", p.getGuideText());
            row.put("source", p.getSource());
            row.put("distanceMeters", Math.round(distance));
            result.add(row);
        });
        return result;
    }

    /** 面交点推荐 Top5：距离为主、安全分与热度为辅；22:00-06:00 只推安全分>=85（无满足项退回全量） */
    public List<TradePointVO> recommend(Long campusId, Long itemId, Long buyerId) {
        List<TradePointVO> all = list(campusId);
        if (all.isEmpty()) {
            return List.of();
        }
        boolean night = LocalTime.now().isAfter(LocalTime.of(22, 0))
                || LocalTime.now().isBefore(LocalTime.of(6, 0));
        if (night) {
            List<TradePointVO> safe = all.stream()
                    .filter(p -> p.getSafetyScore() != null && p.getSafetyScore() >= 85).toList();
            if (!safe.isEmpty()) {
                all = safe;
            }
        }
        BigDecimal refX = null;
        BigDecimal refY = null;
        if (itemId != null) {
            InnerItemVO item = loadItemForRecommend(itemId);
            if (item != null && item.getPreferPointId() != null) {
                TradePoint p = getById(item.getPreferPointId());
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

    private InnerItemVO loadItemForRecommend(Long itemId) {
        try {
            Result<InnerItemVO> result = itemClient.get(itemId);
            if (result != null && result.getCode() == ResultCode.SUCCESS.getCode()) {
                return result.getData();
            }
            log.warn("[map][recommend][{}] 取商品参考点位失败（降级为无距离分）：code={}",
                    itemId, result == null ? "null" : result.getCode());
        } catch (Exception e) {
            log.warn("[map][recommend][{}] 调用 item-service 异常（降级为无距离分）：{}", itemId, e.getMessage());
        }
        return null;
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

    // ---------------------------------------------------------------- 写入

    /** 提交自定义点位（免审核；每次新建、不做同名合并） */
    public TradePoint suggest(PointSuggestDTO dto, Long userId) {
        return insertSuggest(dto, userId, SOURCE_USER);
    }

    /** 用户在地图上新增面交点（发布页/私聊改地址）：每次新建、不做同名合并 */
    public TradePoint createByUser(PointSuggestDTO dto, Long userId) {
        return insertSuggest(dto, userId, SOURCE_USER);
    }

    /**
     * 「高德搜索落库」：先按校区+名称查库，命中直接复用（不重复消耗搜索配额）；否则落库 source=3。
     * 这类点位不会刷屏地图：{@link #listByCampus} 里 source=3 必须挂了商品才出现，且会被空点位清理回收。
     */
    public TradePoint saveSearchHit(PointSuggestDTO dto, Long userId) {
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (StringUtils.hasText(name)) {
            TradePoint existing = pointMapper.selectOne(Wrappers.<TradePoint>lambdaQuery()
                    .eq(TradePoint::getCampusId, dto.getCampusId())
                    .eq(TradePoint::getName, name)
                    .eq(TradePoint::getStatus, STATUS_ENABLED)
                    .last("LIMIT 1"));
            if (existing != null) {
                // 复用别人搜过的结果时把当前用户也记上，否则他在"我提交的点位"里看不到
                if (existing.getCreatorId() == null && userId != null) {
                    TradePoint claim = new TradePoint();
                    claim.setId(existing.getId());
                    claim.setCreatorId(userId);
                    pointMapper.updateById(claim);
                    existing.setCreatorId(userId);
                }
                log.info("[map][search-hit][{}] 命中库内同名点位，跳过落库与高德调用", existing.getId());
                return existing;
            }
        }
        return insertSuggest(dto, userId, SOURCE_AMAP_SEARCH);
    }

    private TradePoint insertSuggest(PointSuggestDTO dto, Long userId, int source) {
        boolean hasGeo = dto.getLng() != null && dto.getLat() != null;
        boolean hasPixel = dto.getCoordX() != null && dto.getCoordY() != null;
        if (!hasGeo && !hasPixel) {
            throw new BizException(ResultCode.PARAM_ERROR, "请在地图上选点");
        }
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (name.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请填写点位名称");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位名称最多 " + NAME_MAX_LENGTH + " 个字");
        }
        TradePoint p = new TradePoint();
        p.setCampusId(dto.getCampusId());
        p.setName(name);
        p.setSource(source);
        p.setPointType(dto.getPointType() == null ? 9 : dto.getPointType());
        p.setCoordX(dto.getCoordX());
        p.setCoordY(dto.getCoordY());
        p.setLng(dto.getLng());
        p.setLat(dto.getLat());
        p.setGuideText(dto.getGuideText());
        // 搜索落库的地点没有运营参考分，给低分（它只是"可选地址"，不是推荐面交点）
        p.setSafetyScore(source == SOURCE_AMAP_SEARCH ? 30 : 40);
        p.setSafeTags("[]");
        p.setStatus(STATUS_ENABLED);
        // 免审核：直接置为通过，否则对方在地图上/私聊里看不到这个新点位
        p.setAuditStatus(1);
        p.setDealCount(0);
        p.setDisputeCount(0);
        p.setCreatorId(userId);
        pointMapper.insert(p);
        // 必须 bump 版本号：点位列表是版本号缓存，否则新点位要等 TTL 才可见
        cache.bumpVersion();
        log.info("[map][create][{}][{}] 新增面交点 source={} name={} lng={} lat={}",
                p.getId(), userId, source, p.getName(), p.getLng(), p.getLat());
        return p;
    }

    /**
     * 空点位清理：点位上没有任何"还有戏"的商品（status 0/1/2/3）且创建超过宽限期（默认 7 天）才删除。
     *
     * <p><b>安全阀</b>：item-service 不可用时**整体跳过**（统计为 null），绝不因为"拿不到商品数"误删点位。
     * 规则与单体一致（同时回收 source=3 的搜索落库点位）；清理后 bump 版本号，前端立即看不到幽灵点位。
     *
     * @return 删除的点位数
     */
    public int cleanupEmptyPoints() {
        List<TradePoint> all = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                .select(TradePoint::getId, TradePoint::getCampusId, TradePoint::getCreatedAt));
        if (all.isEmpty()) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusDays(Math.max(1, props.getCleanupGraceDays()));
        List<Long> campusIds = all.stream().map(TradePoint::getCampusId).filter(Objects::nonNull)
                .distinct().toList();
        int total = 0;
        for (Long campusId : campusIds) {
            Map<Long, PointItemStatVO> stats = itemStats.loadFreshOrNull(campusId);
            if (stats == null) {
                log.warn("[map][cleanup][{}] item-service 不可用，跳过本校区的空点位清理（避免误删）", campusId);
                continue;
            }
            List<Long> emptyIds = all.stream()
                    .filter(p -> campusId.equals(p.getCampusId()))
                    .filter(p -> {
                        PointItemStatVO s = stats.get(p.getId());
                        return s == null || s.resolveItemCount() == 0;
                    })
                    // createdAt 为空（老数据）按"够老"处理，否则永远清不掉
                    .filter(p -> p.getCreatedAt() == null || p.getCreatedAt().isBefore(deadline))
                    .map(TradePoint::getId)
                    .toList();
            if (emptyIds.isEmpty()) {
                continue;
            }
            pointMapper.deleteByIds(emptyIds);
            total += emptyIds.size();
            log.info("[map][cleanup][{}] 清理 {} 个没有商品的点位：{}", campusId, emptyIds.size(), emptyIds);
        }
        if (total > 0) {
            cache.bumpVersion();
        }
        return total;
    }

    /** 点位审核（admin 内部接口）：approve→audit_status=1 / reject→2；不存在抛参数错误 */
    public TradePoint audit(Long id, String action) {
        TradePoint p = getById(id);
        if (p == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "面交点不存在");
        }
        int auditStatus = "reject".equalsIgnoreCase(action) ? 2 : 1;
        TradePoint update = new TradePoint();
        update.setId(id);
        update.setAuditStatus(auditStatus);
        pointMapper.updateById(update);
        cache.bumpVersion();
        p.setAuditStatus(auditStatus);
        log.info("[map][audit][{}] action={} → audit_status={}", id, action, auditStatus);
        return p;
    }

    /** 点位启停（admin 内部接口）：status=1 启用 / 0 停用 */
    public TradePoint updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 只能是 0 或 1");
        }
        TradePoint p = getById(id);
        if (p == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "面交点不存在");
        }
        TradePoint update = new TradePoint();
        update.setId(id);
        update.setStatus(status);
        pointMapper.updateById(update);
        cache.bumpVersion();
        p.setStatus(status);
        log.info("[map][status][{}] → status={}", id, status);
        return p;
    }

    /** 我提交的点位（含待审，仅自己可见；发布页用它保证刚新增的地点刷新后仍能选中） */
    public List<TradePoint> listMine(Long campusId, Long userId) {
        return pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                .eq(TradePoint::getCampusId, campusId)
                .eq(TradePoint::getCreatorId, userId)
                .eq(TradePoint::getStatus, STATUS_ENABLED)
                .orderByDesc(TradePoint::getId));
    }

    public List<TradePointVO> listMineVO(Long campusId, Long userId) {
        return listMine(campusId, userId).stream().map(this::toVO).toList();
    }

    // ---------------------------------------------------------------- 内部接口 / 转换

    /** 管理端点位列表（inner）：campusId/status 均可选；含商品统计（admin 展示用） */
    public List<InnerPointVO> innerList(Long campusId, Integer status) {
        List<TradePoint> points = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                .eq(campusId != null, TradePoint::getCampusId, campusId)
                .eq(status != null, TradePoint::getStatus, status)
                .orderByAsc(TradePoint::getId));
        Map<Long, PointItemStatVO> stats = new LinkedHashMap<>();
        if (campusId != null) {
            stats = itemStats.statsByCampus(campusId);
        }
        List<InnerPointVO> out = new ArrayList<>(points.size());
        for (TradePoint p : points) {
            PointItemStatVO s = stats.get(p.getId());
            out.add(toInnerVO(p, s));
        }
        return out;
    }

    public InnerPointVO innerDetail(Long id) {
        TradePoint p = getById(id);
        if (p == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "面交点不存在");
        }
        PointItemStatVO s = p.getCampusId() == null ? null
                : itemStats.statsByCampus(p.getCampusId()).get(p.getId());
        return toInnerVO(p, s);
    }

    /** 管理端点位统计：total=全部点位；pending=待审(audit_status=0)；disabled=停用(status=0) */
    public Map<String, Object> innerStats() {
        long total = pointMapper.selectCount(Wrappers.<TradePoint>lambdaQuery());
        long pending = pointMapper.selectCount(Wrappers.<TradePoint>lambdaQuery()
                .eq(TradePoint::getAuditStatus, 0));
        long disabled = pointMapper.selectCount(Wrappers.<TradePoint>lambdaQuery()
                .eq(TradePoint::getStatus, 0));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pending", pending);
        out.put("disabled", disabled);
        return out;
    }

    private InnerPointVO toInnerVO(TradePoint p, PointItemStatVO stat) {
        InnerPointVO vo = new InnerPointVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setGuide(p.getGuideText());
        vo.setLongitude(p.getLng());
        vo.setLatitude(p.getLat());
        vo.setCampusId(p.getCampusId());
        vo.setType(p.getPointType());
        vo.setStatus(p.getStatus());
        vo.setAuditStatus(p.getAuditStatus());
        vo.setSource(p.getSource());
        vo.setSafetyScore(p.getSafetyScore());
        vo.setSafeTags(readList(p.getSafeTags()));
        vo.setCoordX(p.getCoordX());
        vo.setCoordY(p.getCoordY());
        if (stat != null) {
            vo.setItemCount(stat.resolveItemCount());
            vo.setOnSaleCount(stat.resolveOnSaleCount());
            vo.setMinPrice(stat.getMinPrice());
        }
        vo.setCreatedAt(p.getCreatedAt());
        vo.setUpdatedAt(p.getUpdatedAt());
        return vo;
    }

    /** 单个点位转 VO（不含商品统计，新建/回读点位时用，与单体一致 itemCount=0） */
    public TradePointVO toVO(TradePoint p) {
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
        vo.setAuditStatus(p.getAuditStatus());
        vo.setSource(p.getSource());
        vo.setSafeTags(readList(p.getSafeTags()));
        vo.setAvailablePeriod(readList(p.getAvailablePeriod()));
        vo.setItemCount(0);
        vo.setOnSaleCount(0);
        return vo;
    }

    /** 点位 + 商品数/最低价：统计来自 item-service（不可用时全 0，不是 500） */
    private List<TradePointVO> withItemCount(List<TradePoint> points, Long campusId) {
        if (points.isEmpty()) {
            return List.of();
        }
        Map<Long, PointItemStatVO> stats = itemStats.statsByCampus(campusId);
        return points.stream().map(p -> {
            TradePointVO vo = toVO(p);
            PointItemStatVO s = stats.get(p.getId());
            vo.setItemCount(s == null ? 0 : s.resolveItemCount());
            vo.setOnSaleCount(s == null ? 0 : s.resolveOnSaleCount());
            vo.setMinPrice(s == null ? null : s.getMinPrice());
            return vo;
        }).sorted(Comparator.comparingInt((TradePointVO v) ->
                v.getItemCount() == null ? 0 : v.getItemCount()).reversed()).toList();
    }

    private boolean isSearchHit(TradePoint p) {
        return p.getSource() != null && p.getSource() == SOURCE_AMAP_SEARCH;
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

    /** 供 Feign/内部使用：按 id 取点位（不抛异常，返回 null） */
    public TradePoint findByIdOrNull(Long id) {
        return getById(id);
    }

    /** 保留常量语义：点位来源字段（其它服务/文档引用） */
    public static int sourceOf(TradePoint p) {
        return p.getSource() == null ? SOURCE_OFFICIAL : p.getSource();
    }

    /** 活跃商品状态（文档化用，实际统计由 item-service 提供） */
    public static List<Integer> activeItemStatuses() {
        return ITEM_ACTIVE_STATUS;
    }
}
