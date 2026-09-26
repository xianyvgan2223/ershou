package com.ershou.campus.common.cache;

import com.ershou.campus.common.util.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 缓存失效统一出口（开发文档 4.7：先更新 DB 再删缓存）。
 *
 * <p>所有写路径都从这里删缓存，避免以后新增写路径时漏删。调用时机统一由
 * {@link com.ershou.campus.common.util.TxHooks#afterCommit} 保证在事务提交之后，
 * 否则"事务里删缓存 + 并发读回填"会把旧值又写回缓存。
 *
 * <p>地图点位缓存用<b>版本号</b>失效：{@code ershou:map:points:ver} 一自增，
 * 所有 {campusId} 的旧 key 立刻变成不可达（各自按 TTL 自然过期），
 * 不需要 KEYS/SCAN，也不存在"删一半又被回填"的竞态。
 *
 * <p>失效失败只记日志不抛异常：缓存脏了最多活到 TTL 到期，不能因此让写请求失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheEvictor {

    private final StringRedisTemplate redis;

    /** 商品字段变化（状态 / 价格 / 浏览量 / 收藏数）：删详情缓存 + 地图计数改版 */
    public void itemChanged(Long itemId) {
        item(itemId);
        bumpMapVersion();
    }

    public void itemsChanged(Collection<Long> itemIds) {
        items(itemIds);
        bumpMapVersion();
    }

    /** 只影响商品卡片自身、不影响点位在售数（例如收藏数变化） */
    public void item(Long itemId) {
        if (itemId == null) {
            return;
        }
        deleteKeys(List.of(RedisKeys.itemDetail(itemId), RedisKeys.itemNull(itemId)));
    }

    public void items(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(itemIds.size() * 2);
        for (Long id : itemIds) {
            if (id == null) {
                continue;
            }
            keys.add(RedisKeys.itemDetail(id));
            keys.add(RedisKeys.itemNull(id));
        }
        deleteKeys(keys);
    }

    /** 点位信息变化（运营审核、用户提点）：删字典缓存 + 地图改版 */
    public void pointChanged(Long pointId) {
        if (pointId != null) {
            deleteKeys(List.of(RedisKeys.dictPoint(pointId)));
        }
        bumpMapVersion();
    }

    public void categoryChanged(Long categoryId) {
        if (categoryId != null) {
            deleteKeys(List.of(RedisKeys.dictCategory(categoryId)));
        }
    }

    public void schoolChanged(Long schoolId) {
        if (schoolId != null) {
            deleteKeys(List.of(RedisKeys.dictSchool(schoolId)));
        }
    }

    public void campusChanged(Long campusId) {
        if (campusId != null) {
            deleteKeys(List.of(RedisKeys.dictCampus(campusId)));
        }
        bumpMapVersion();
    }

    /** 当前地图缓存版本号：读接口把它拼进 key，写接口自增它 */
    public long mapVersion() {
        try {
            String raw = redis.opsForValue().get(RedisKeys.mapPointsVersion());
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (Exception e) {
            log.warn("[cache][map-ver] 读取版本号失败，按 0 处理：{}", e.getMessage());
            return 0L;
        }
    }

    public void bumpMapVersion() {
        try {
            redis.opsForValue().increment(RedisKeys.mapPointsVersion());
        } catch (Exception e) {
            log.warn("[cache][map-ver] 版本号自增失败：{}", e.getMessage());
        }
    }

    private void deleteKeys(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try {
            redis.delete(keys);
        } catch (Exception e) {
            log.warn("[cache][evict] 删除失败，等 TTL 自然过期 keys={}：{}", keys.size(), e.getMessage());
        }
    }
}
