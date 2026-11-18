package com.ershou.cloud.item.support.cache;

import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.entity.ItemCategory;
import com.ershou.cloud.item.mapper.ItemCategoryMapper;
import com.ershou.cloud.item.support.RemoteService;
import com.ershou.cloud.item.vo.PointVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 字典读取（分类 / 面交点）——旧单体 {@code common/cache/DictionaryCache} 的微服务版。
 *
 * <p>差异：旧单体用 Redis 缓存分类与点位（跨库共享）；拆分后分类在本服务 DB（直接查，
 * 单次批量 SQL 足够便宜），点位属 map-service（走 {@link RemoteService} 并降级）。
 * 因此本类不再持有 Redis 字典缓存，避免"跨服务缓存不一致"问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DictionaryCache {

    private final ItemCategoryMapper categoryMapper;
    private final RemoteService remote;

    public String categoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryNames(List.of(categoryId)).get(categoryId);
    }

    /** 批量分类名（一次 selectBatchIds，避免 N+1） */
    public Map<Long, String> categoryNames(Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = categoryIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, String> names = new HashMap<>(ids.size());
            for (ItemCategory c : categoryMapper.selectBatchIds(ids)) {
                if (c.getName() != null) {
                    names.put(c.getId(), c.getName());
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("[dict][category] 分类查询失败，降级空：{}", e.getMessage());
            return Map.of();
        }
    }

    public PointVO point(Long pointId) {
        return remote.point(pointId);
    }

    /** 批量点位（列表场景：按商品集合经 map-service 补齐，失败降级空 Map） */
    public Map<Long, PointVO> points(List<Item> items) {
        return remote.pointsForItems(items);
    }
}
