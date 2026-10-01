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
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradePointService {

    private static final TypeReference<List<TradePointVO>> POINT_LIST = new TypeReference<>() {
    };

    /**
     * 商品的"还有戏"状态：草稿 / 审核中 / 在售 / 已锁定。
     * 只有这些状态的商品才值得占着一个面交点；已售出、已下架、过期、违规、驳回都算终结态。
     */
    private static final List<Integer> ITEM_ACTIVE_STATUS = List.of(0, 1, 2, 3);
    /** 空点位宽限期（天）：刚建的点位可能还没挂商品，别立刻删掉 */
    private static final int EMPTY_POINT_GRACE_DAYS = 7;
    /** 点位名称长度上限：与前端输入框 maxlength 一致（表里 VARCHAR(64)，收紧是为了地图标签别太长） */
    private static final int NAME_MAX_LENGTH = 30;
    /** 点位来源：1=官方开通，2=用户提交，3=高德搜索落库 */
    public static final int SOURCE_OFFICIAL = 1;
    public static final int SOURCE_USER = 2;
    public static final int SOURCE_AMAP_SEARCH = 3;

    private final TradePointMapper pointMapper;
    private final ItemMapper itemMapper;
    private final CampusMapper campusMapper;
    private final ObjectMapper json;
    private final JsonCache cache;
    private final CacheEvictor evictor;
    private final DictionaryCache dict;
    private final CacheProperties cacheProps;
    /** 附近点位检索（Redis GEO）：点位数据仍在 MySQL，这里只是"按坐标找"的索引 */
    private final PointGeoIndex geoIndex;

    public TradePoint getById(Long id) {
        return dict.point(id);
    }

    /**
     * 「我附近的点位」：按坐标找半径内的面交点，按距离从近到远。
     *
     * <p>走 Redis GEO（{@link PointGeoIndex}），**不消耗任何高德配额**——
     * 坐标来自浏览器定位（也是 0 配额），检索在 Redis 内完成。
     * 这是"附近"这个最高频的需求，所以刻意不让它依赖高德的周边搜索（5000 次/月）。
     *
     * @param radius 半径（米），调用方传；服务端夹在 [50, 5000] 之间防滥用
     * @return 每个点位附带 {@code distanceMeters}
     */
    public List<Map<String, Object>> nearbyPoints(Long campusId, double lng, double lat,
                                                  Integer radius, Integer limit) {
        int r = radius == null ? 500 : Math.max(50, Math.min(radius, 5000));
        int size = limit == null ? 20 : Math.max(1, Math.min(limit, 50));
        Map<Long, Double> ordered = geoIndex.nearby(campusId, lng, lat, r, size);
        if (ordered.isEmpty()) {
            return List.of();
        }
        // 可见性规则与 listByCampus 完全一致：搜索落库（source=3）的点位只有挂了商品才出现，
        // 否则"搜过的地名"会污染附近列表
        Set<Long> visibleSearchHits = activeItemsByPoint(campusId).keySet();
        Map<Long, TradePoint> byId = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                        .in(TradePoint::getId, ordered.keySet())
                        .eq(TradePoint::getStatus, 1))
                .stream()
                .filter(p -> !isSearchHit(p) || visibleSearchHits.contains(p.getId()))
                .collect(Collectors.toMap(TradePoint::getId, p -> p, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        ordered.forEach((pointId, distance) -> {
            TradePoint p = byId.get(pointId);
            if (p == null) {
                return; // 已被删除或不可见：跳过，不报错
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

    /** 是不是"高德搜索落库"的点位（source=3） */
    private boolean isSearchHit(TradePoint p) {
        return p.getSource() != null && p.getSource() == SOURCE_AMAP_SEARCH;
    }

    /**
     * 地图/下拉用的点位列表。
     *
     * <p>**不做审核过滤**：产品决定（2026-09-30）自定义面交点免审核，原来的 `audit_status=1`
     * 过滤会让新点位只能提交者自己看到，"私聊里改面交地址"那一端就看不到对方新加的点位了。
     *
     * <p>**但过滤"搜过但没用上"的地点**：`AMap.PlaceSearch` 的结果会以 source=3 落库
     * （为了以后不重复花搜索配额）。这些地名可能成千上万（一条街上的每个店），
     * 全画到地图上就彻底没法看了。所以：
     *
     * <pre>
     *   显示 = 官方(1) ∪ 用户提交(2)            // 这些是"公认的面交点"
     *        ∪ source=3 但已经挂了商品          // 搜出来的地名一旦真被用来交易，就转正
     * </pre>
     *
     * 搜索落库、又一直没被用上的点位，会由 {@link #cleanupEmptyPoints()} 在宽限期后回收。
     */
    public List<TradePoint> listByCampus(Long campusId) {
        return listByCampus(campusId, false);
    }

    /**
     * @param onlyWithItems 地图场景传 true：**没商品的点位一个都不画**（地图是用来"找货"的，
     *                      一堆 0 件气泡纯属干扰）。发布页必须传 false —— 那里要能选到
     *                      "刚建好还没挂商品"的点位，过滤了用户就选不到自己刚建的点。
     *                      搜索落库（source=3）的点位无论哪个开关都要挂了商品才出现。
     */
    public List<TradePoint> listByCampus(Long campusId, boolean onlyWithItems) {
        List<TradePoint> all = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                .eq(TradePoint::getCampusId, campusId)
                .eq(TradePoint::getStatus, 1)
                .orderByDesc(TradePoint::getSafetyScore));
        Set<Long> usedPointIds = activeItemsByPoint(campusId).keySet();
        return all.stream()
                .filter(p -> {
                    boolean hasItems = usedPointIds.contains(p.getId());
                    // 搜过没用上的地名：任何场景都不显示
                    if (isSearchHit(p) && !hasItems) {
                        return false;
                    }
                    // 地图场景：没商品的一律不画
                    return !onlyWithItems || hasItems;
                })
                .toList();
    }

    /** 地图页数据：底图配置 + 点位 + 每个点位的在售商品数/最低价。**不含 0 商品的点位。** */
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
        // 地图只画有商品的点位（找货用），所以走"可见"那份缓存
        vo.setPoints(cachedPoints(campusId, true));
        return vo;
    }

    /** 点位列表（发布页/私聊选点用）：**保留 0 商品的点位**，否则用户刚建的点选不到 */
    public List<TradePointVO> list(Long campusId) {
        return cachedPoints(campusId, false);
    }

    /**
     * 点位 + 在售数 / 最低价：整校区的在售商品聚合，原本每次请求都要把全校区在售商品捞进内存。
     * 缓存 key 带版本号（{@link CacheEvictor#mapVersion()}），商品写路径自增版本号即整体失效。
     *
     * <p>⚠️ **两种可见性必须用两个 key**：地图（只画有商品的）与发布页（要含 0 商品的）
     * 结果不同，共用一份缓存会导致"地图上又冒出空气泡"（或反过来发布页选不到新点）。
     */
    private List<TradePointVO> cachedPoints(Long campusId) {
        return cachedPoints(campusId, false);
    }

    private List<TradePointVO> cachedPoints(Long campusId, boolean onlyWithItems) {
        long version = evictor.mapVersion();
        String key = onlyWithItems
                ? RedisKeys.mapPointsWithItems(campusId, version)
                : RedisKeys.mapPoints(campusId, version);
        return cache.getOrLoad(key, cacheProps.getMapPointsTtl(), POINT_LIST,
                () -> withItemCount(listByCampus(campusId, onlyWithItems), campusId));
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

    /**
     * 提交自定义点位。
     *
     * <p>与 {@link #createByUser} 一样：**不做同名合并**，每次调用都新建一条。
     * 返回创建结果，前端可以直接把点位插进列表。
     */
    public TradePoint suggest(PointSuggestDTO dto, Long userId) {
        return insertSuggest(dto, userId, SOURCE_USER);
    }

    /**
     * 用户在地图上新增面交点（发布页 / 私聊改地址用）。
     *
     * <p><b>不做任何合并 / 同名复用</b>：以前同名会直接返回已有点位，结果用户在发布页
     * 明明选了新地址（可能只是名字撞了），却被"合并"到旧点位上，看起来就像没生效。
     * 现在每次调用都新建一条，点位各自的 id / 坐标完全独立。
     *
     * <p>重复点位由运营在后台按 id 删除（`DELETE /admin` 侧或直接删库），
     * 不做隐式合并——隐式合并比多几条脏数据更难排查。
     */
    public TradePoint createByUser(PointSuggestDTO dto, Long userId) {
        return insertSuggest(dto, userId, SOURCE_USER);
    }

    /**
     * 「高德搜索落库」：把用户在高德上搜到的地点存进点位库，**来源标记为
     * {@link #SOURCE_AMAP_SEARCH}（3）**，供以后直接查库、不再消耗搜索配额。
     *
     * <p>与用户自建点位的区别只有两点：
     * <ol>
     *   <li>source=3，前端/运营能区分它是"搜出来的地名"；</li>
     *   <li>**先在库里按校区+名字找一遍**，已有就复用——这正是"先查库再调高德"的落点，
     *       同一个名字第二次搜索会直接命中数据库，0 次高德调用。</li>
     * </ol>
     *
     * <p>为什么这类点位不会把地图刷屏：地图气泡只显示"有商品的点位"或"非搜索落库的点位"
     * （见 {@code listByCampus}），搜索落库但一直没挂商品的点位不会出现在地图上，
     * 也会被空点位清理任务回收。
     */
    public TradePoint saveSearchHit(PointSuggestDTO dto, Long userId) {
        String name = dto.getName() == null ? "" : dto.getName().trim();
        if (StringUtils.hasText(name)) {
            TradePoint existing = pointMapper.selectOne(Wrappers.<TradePoint>lambdaQuery()
                    .eq(TradePoint::getCampusId, dto.getCampusId())
                    .eq(TradePoint::getName, name)
                    .eq(TradePoint::getStatus, 1)
                    .last("LIMIT 1"));
            if (existing != null) {
                // 复用别人搜过的结果时，把当前用户也记上，否则他在"我提交的点位"里看不到，
                // 下次选点还得再搜一遍（等于缓存对他没生效）
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
        // 与前端输入框的 maxlength 保持一致；表里是 VARCHAR(64)，这里收紧到 30 以免地图标签过长
        String name = dto.getName() == null ? "" : dto.getName().trim();
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
        // 搜索落库的地点没有运营给的参考分，给个低分（它只是"可选的地址"，不是推荐面交点）
        p.setSafetyScore(source == SOURCE_AMAP_SEARCH ? 30 : 40);
        p.setSafeTags("[]");
        p.setStatus(1);
        // 免审核：直接置为通过，否则对方在地图上/私聊里看不到这个新点位
        p.setAuditStatus(1);
        p.setDealCount(0);
        p.setDisputeCount(0);
        p.setCreatorId(userId);
        pointMapper.insert(p);
        // 必须清缓存：地图点位列表带版本号缓存（30 分钟），不 bump 的话新点位在别人那儿看不见
        evictor.pointChanged(p.getId());
        log.info("[map][create][{}][{}] 新增面交点 source={} name={} lng={} lat={}",
                p.getId(), userId, source, p.getName(), p.getLng(), p.getLat());
        return p;
    }

    /**
     * 用户自己提交的点位（含待审）。
     * 只查自己，所以 pending 点位不会泄露给他人；发布页用它保证"刚新增的地点刷完页面还能选中"。
     */
    public List<TradePoint> listMine(Long campusId, Long userId) {
        return pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                .eq(TradePoint::getCampusId, campusId)
                .eq(TradePoint::getCreatorId, userId)
                .eq(TradePoint::getStatus, 1)
                .orderByDesc(TradePoint::getId));
    }

    public List<TradePointVO> listMineVO(Long campusId, Long userId) {
        return listMine(campusId, userId).stream().map(this::toVO).toList();
    }

    /** 单个点位转 VO（不含在售统计，新建/回读点位时用） */
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
        return vo;
    }

    private List<TradePointVO> withItemCount(List<TradePoint> points, Long campusId) {
        if (points.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Item>> grouped = activeItemsByPoint(campusId);
        return points.stream().map(p -> {
            TradePointVO vo = toVO(p);
            List<Item> list = grouped.getOrDefault(p.getId(), List.of());
            vo.setItemCount(list.size());
            vo.setMinPrice(list.stream().map(Item::getPrice).filter(Objects::nonNull)
                    .min(BigDecimal::compareTo).orElse(null));
            return vo;
        }).sorted(Comparator.comparing(TradePointVO::getItemCount).reversed()).toList();
    }

    /**
     * 取一个校区里"还有戏"的商品，按面交点分组。
     *
     * <p>只捞 id / preferPointId / price 三列（不 select *），所以即使一个校区几万件在售，
     * 传输量也可控；配合 (campus_id, status) 索引，现算一次聚合是几十毫秒级。
     */
    private Map<Long, List<Item>> activeItemsByPoint(Long campusId) {
        List<Item> items = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .eq(Item::getCampusId, campusId)
                .in(Item::getStatus, ITEM_ACTIVE_STATUS)
                .isNotNull(Item::getPreferPointId)
                .select(Item::getId, Item::getPreferPointId, Item::getPrice));
        return items.stream().collect(Collectors.groupingBy(Item::getPreferPointId));
    }

    /** 点位 id → 该点位上"还有戏"的商品数（清理空点位时用，避免逐个点位查库） */
    public Map<Long, Long> countActiveItemsByPoint(Long campusId) {
        return activeItemsByPoint(campusId).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (long) e.getValue().size()));
    }

    /**
     * 清理"没有商品"的面交点（每天 03:10 由定时任务调用，也可手动触发）。
     *
     * <p>规则：一个点位上**没有任何"还有戏"的商品**（草稿/审核中/在售/已锁定），
     * 且点位本身创建超过 {@value #EMPTY_POINT_GRACE_DAYS} 天，才删除。
     *
     * <p>为什么留宽限期：用户刚在地图上新增的点位，可能过一会儿才发布商品挂上去；
     * 一建完就被清掉会让人以为"新增失败"。
     *
     * <p>为什么按"还有戏"而不是"在售"：商品在审核中就直接把点位删了，
     * 审核通过后面交点就没了 —— 商品详情的面交点会变成空白。
     *
     * <p>这条规则同时回收「高德搜索落库」的点位（source=3）：用户搜过的地名如果一直没被
     * 用来交易，宽限期后自动消失，点位库不会无限膨胀。
     */
    @Transactional(rollbackFor = Exception.class)
    public int cleanupEmptyPoints() {
        List<Campus> campuses = campusMapper.selectList(Wrappers.<Campus>lambdaQuery()
                .select(Campus::getId));
        LocalDateTime deadline = LocalDateTime.now().minusDays(EMPTY_POINT_GRACE_DAYS);
        int total = 0;
        for (Campus campus : campuses) {
            Map<Long, Long> counts = countActiveItemsByPoint(campus.getId());
            List<TradePoint> all = pointMapper.selectList(Wrappers.<TradePoint>lambdaQuery()
                    .eq(TradePoint::getCampusId, campus.getId())
                    .select(TradePoint::getId, TradePoint::getCreatedAt));
            List<Long> emptyIds = all.stream()
                    .filter(p -> counts.getOrDefault(p.getId(), 0L) == 0L)
                    // createdAt 为空（老数据）时按"够老"处理，否则永远清不掉
                    .filter(p -> p.getCreatedAt() == null || p.getCreatedAt().isBefore(deadline))
                    .map(TradePoint::getId)
                    .toList();
            if (emptyIds.isEmpty()) {
                continue;
            }
            pointMapper.deleteByIds(emptyIds);
            total += emptyIds.size();
            log.info("[map][cleanup][{}] 清理 {} 个没有商品的点位：{}", campus.getId(), emptyIds.size(), emptyIds);
        }
        if (total > 0) {
            // 地图点位列表是版本号缓存，删完必须 bump，否则前端还能看到幽灵点位
            evictor.bumpMapVersion();
        }
        return total;
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
