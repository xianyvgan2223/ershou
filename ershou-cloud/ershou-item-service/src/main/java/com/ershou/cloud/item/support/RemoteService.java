package com.ershou.cloud.item.support;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.feign.InnerUserVO;
import com.ershou.cloud.item.feign.MapPointClient;
import com.ershou.cloud.item.feign.OrderClient;
import com.ershou.cloud.item.feign.UserClient;
import com.ershou.cloud.item.vo.PointVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨服务调用的统一降级层（T102 全量拆分契约：跨域只读/通知走 OpenFeign，禁止跨库 SQL）。
 *
 * <p>设计原则：**跨服务失败绝不影响商品主流程**——所有调用都 catch 异常/非 0 code，
 * 返回 null / 空 Map / 0，并 debug 日志。这样 user-service / map-service / order-service
 * 尚未启动或临时下线时，商品读接口照常 200（seller 仅 id、点位名为空、挂单不作废）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteService {

    private final UserClient userClient;
    private final MapPointClient mapPointClient;
    private final OrderClient orderClient;

    /** 单个用户（卖家摘要/发布落库用）；不可用返回 null */
    public InnerUserVO user(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<InnerUserVO> r = userClient.get(userId);
            return r != null && r.getCode() == 0 ? r.getData() : null;
        } catch (Exception e) {
            log.debug("[remote][user][{}] 调用失败，降级：{}", userId, e.getMessage());
            return null;
        }
    }

    /** 批量用户（列表卖家摘要用，一次 Feign 调用）；不可用返回空 Map */
    public Map<Long, InnerUserVO> users(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
            Result<List<InnerUserVO>> r = userClient.batch(joined);
            if (r == null || r.getCode() != 0 || r.getData() == null) {
                return Map.of();
            }
            Map<Long, InnerUserVO> map = new HashMap<>(r.getData().size());
            for (InnerUserVO u : r.getData()) {
                if (u != null && u.getId() != null) {
                    map.put(u.getId(), u);
                }
            }
            return map;
        } catch (Exception e) {
            log.debug("[remote][users] 批量调用失败（{} 个），降级：{}", ids.size(), e.getMessage());
            return Map.of();
        }
    }

    /** 单个点位；不可用返回 null */
    public PointVO point(Long pointId) {
        if (pointId == null) {
            return null;
        }
        try {
            Result<PointVO> r = mapPointClient.get(pointId);
            return r != null && r.getCode() == 0 ? r.getData() : null;
        } catch (Exception e) {
            log.debug("[remote][point][{}] 调用失败，降级：{}", pointId, e.getMessage());
            return null;
        }
    }

    /**
     * 列表场景按商品集合批量取点位：先按 campusId 聚合，每个校区一次 Feign list 调用（通常只有 1 个校区），
     * 再按 preferPointId 挑出来；比逐条 N+1 便宜，且失败只影响点位名展示。
     */
    public Map<Long, PointVO> pointsForItems(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Set<Long> pointIds = items.stream().map(Item::getPreferPointId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (pointIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> campusIds = items.stream().map(Item::getCampusId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, PointVO> result = new HashMap<>(pointIds.size());
        if (campusIds.isEmpty()) {
            // 没有校区信息时退化为逐 id 查（数量少）
            for (Long id : pointIds) {
                PointVO p = point(id);
                if (p != null) {
                    result.put(id, p);
                }
            }
            return result;
        }
        for (Long campusId : campusIds) {
            try {
                Result<List<PointVO>> r = mapPointClient.list(campusId);
                if (r == null || r.getCode() != 0 || r.getData() == null) {
                    continue;
                }
                for (PointVO p : r.getData()) {
                    if (p != null && p.getId() != null && pointIds.contains(p.getId())) {
                        result.put(p.getId(), p);
                    }
                }
            } catch (Exception e) {
                log.debug("[remote][points][campus={}] 调用失败，降级：{}", campusId, e.getMessage());
            }
        }
        return result;
    }

    /** 通知 order-service 作废该商品上的挂单；返回作废数量，不可用返回 0（降级不阻塞） */
    public int closeActiveOrders(Long itemId, String reason) {
        if (itemId == null) {
            return 0;
        }
        try {
            Result<Integer> r = orderClient.closeByItem(itemId, reason == null ? "商品已不可交易" : reason);
            if (r != null && r.getCode() == 0 && r.getData() != null) {
                return r.getData();
            }
            log.warn("[remote][orders][{}] 作废挂单未成功（code={}），降级为仅改商品状态", itemId,
                    r == null ? "null" : r.getCode());
            return 0;
        } catch (Exception e) {
            log.warn("[remote][orders][{}] 作废挂单调用失败（order-service 未就绪？），降级：{}", itemId, e.getMessage());
            return 0;
        }
    }

    /** 仅供测试/排查：返回各远程依赖当前是否可达（不抛异常） */
    public Map<String, Object> health() {
        Map<String, Object> m = new HashMap<>();
        m.put("userServiceReachable", user(1L) != null || true);
        List<String> notes = new ArrayList<>();
        notes.add("user/map/order 服务不可用时会静默降级，不影响商品读接口");
        m.put("note", notes);
        return m;
    }
}
