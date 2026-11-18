package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 地图/面交点服务 OpenFeign 客户端（full-split-contract 第三节 map-service 内部接口）。
 *
 * <p>用途：面交单点位快照、改面交地点时的点位校验（同校区）、列表/详情的点位名与指引。
 * map-service 未就绪时 fallback 返回 90001：点位名降级为 null（前端显示"待协商"），
 * 改点/创建时若指定 pointId 则按"无法校验"放行并记 warn（保证迁移期功能可用）。
 */
@FeignClient(name = "ershou-map-service", contextId = "pointClient", path = "/inner/points",
        fallback = PointClientFallback.class)
public interface PointClient {

    @GetMapping("/{id}")
    Result<InnerPointVO> get(@PathVariable("id") Long id);
}
