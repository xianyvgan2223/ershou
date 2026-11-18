package com.ershou.cloud.map.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 OpenFeign 客户端（契约：full-split-contract.md 第六节 map-service 依赖/第三节 item-service 内部接口）。
 *
 * <ul>
 *   <li>{@code GET /inner/items/count-by-point?campusId=} → 每个点位的商品数（本服务用于地图气泡/列表/清理）；
 *       契约最小字段 {@code {pointId,count}}，额外支持 itemCount/onSaleCount/minPrice 增强字段；</li>
 *   <li>{@code GET /inner/items/by-point?campusId=&pointId=&page=&pageSize=} → 点位商品列表
 *       （通过本服务 {@code /inner/points/{id}/items} 透出，供其它服务拼装用）。</li>
 * </ul>
 *
 * <p>下游不可用时走 {@link ItemClientFallback}（Sentinel，需 Nacos {@code feign.sentinel.enabled=true}）：
 * 返回 code=80001「服务暂不可用」，本服务业务层再降级为"无统计"而不是 500。
 */
@FeignClient(name = "ershou-item-service", contextId = "mapItemClient", path = "/inner/items",
        fallback = ItemClientFallback.class)
public interface ItemClient {

    /** 商品详情快照（本服务只用 preferPointId 做"按商品推荐面交点"的参考坐标，其余字段忽略） */
    @GetMapping("/{id}")
    Result<InnerItemVO> get(@PathVariable("id") Long id);

    @GetMapping("/count-by-point")
    Result<List<PointItemStatVO>> countByPoint(@RequestParam("campusId") Long campusId);

    @GetMapping("/by-point")
    Result<Object> byPoint(@RequestParam("campusId") Long campusId,
                           @RequestParam(value = "pointId", required = false) Long pointId,
                           @RequestParam(value = "page", defaultValue = "1") long page,
                           @RequestParam(value = "pageSize", defaultValue = "20") long pageSize);
}
