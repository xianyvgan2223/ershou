package com.ershou.campus.common.cache;

import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.item.entity.ItemCategory;
import com.ershou.campus.module.item.mapper.ItemCategoryMapper;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.entity.School;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.ershou.campus.module.school.mapper.SchoolMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 基础字典缓存：分类 / 点位 / 学校 / 校区。
 *
 * <p>这些数据读多写极少（分类、学校、校区基本不变，点位只在审核时变），
 * 是商品列表 / 详情 / 地图 / 面交单组装时 N+1 查询的主要来源。
 * 单条走 GET，批量走 MGET，缺失的部分才回源 DB 并回填，库里也没有的写空值缓存。
 */
@Service
@RequiredArgsConstructor
public class DictionaryCache {

    private static final TypeReference<ItemCategory> CATEGORY = new TypeReference<>() {
    };
    private static final TypeReference<TradePoint> POINT = new TypeReference<>() {
    };
    private static final TypeReference<School> SCHOOL = new TypeReference<>() {
    };
    private static final TypeReference<Campus> CAMPUS = new TypeReference<>() {
    };

    private final ItemCategoryMapper categoryMapper;
    private final TradePointMapper pointMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final JsonCache cache;
    private final CacheProperties props;

    // ------------------------------------------------------------ 分类

    public ItemCategory category(Long id) {
        if (id == null) {
            return null;
        }
        return cache.getOrLoad(RedisKeys.dictCategory(id), props.getDictTtl(), CATEGORY,
                () -> categoryMapper.selectById(id));
    }

    public String categoryName(Long id) {
        ItemCategory category = category(id);
        return category == null ? null : category.getName();
    }

    /** 批量取分类名：列表页一次 MGET 拿全，替代逐条 selectById */
    public Map<Long, String> categoryNames(Collection<Long> ids) {
        Map<Long, ItemCategory> categories = batch(ids, CATEGORY, RedisKeys::dictCategory,
                categoryMapper::selectBatchIds, ItemCategory::getId);
        if (categories.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>(categories.size());
        categories.forEach((id, category) -> names.put(id, category.getName()));
        return names;
    }

    // ------------------------------------------------------------ 点位

    public TradePoint point(Long id) {
        if (id == null) {
            return null;
        }
        return cache.getOrLoad(RedisKeys.dictPoint(id), props.getDictTtl(), POINT,
                () -> pointMapper.selectById(id));
    }

    public Map<Long, TradePoint> points(Collection<Long> ids) {
        return batch(ids, POINT, RedisKeys::dictPoint, pointMapper::selectBatchIds, TradePoint::getId);
    }

    // ------------------------------------------------------------ 学校 / 校区

    public School school(Long id) {
        if (id == null) {
            return null;
        }
        return cache.getOrLoad(RedisKeys.dictSchool(id), props.getDictTtl(), SCHOOL,
                () -> schoolMapper.selectById(id));
    }

    public Campus campus(Long id) {
        if (id == null) {
            return null;
        }
        return cache.getOrLoad(RedisKeys.dictCampus(id), props.getDictTtl(), CAMPUS,
                () -> campusMapper.selectById(id));
    }

    // ------------------------------------------------------------ 内部

    private <T> Map<Long, T> batch(Collection<Long> ids, TypeReference<T> type, Function<Long, String> keyFn,
                                   Function<Collection<Long>, List<T>> dbLoader, Function<T, Long> idFn) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<String> keys = distinct.stream().map(keyFn).toList();
        List<JsonCache.Hit<T>> hits = cache.multiGet(keys, type);
        Map<Long, T> result = new HashMap<>(distinct.size());
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            JsonCache.Hit<T> hit = hits.get(i);
            if (!hit.hit()) {
                missing.add(distinct.get(i));
            } else if (hit.value() != null) {
                result.put(distinct.get(i), hit.value());
            }
        }
        if (missing.isEmpty()) {
            return result;
        }
        for (T row : dbLoader.apply(missing)) {
            Long id = idFn.apply(row);
            result.put(id, row);
            cache.set(keyFn.apply(id), row, props.getDictTtl());
        }
        for (Long id : missing) {
            if (!result.containsKey(id)) {
                cache.setNull(keyFn.apply(id), props.getNullValueTtl());
            }
        }
        return result;
    }
}
