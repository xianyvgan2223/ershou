package com.ershou.cloud.item.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.item.vo.PointVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * map-service 内部接口（契约 §三 map-service）。
 *
 * <p>商品详情/卡片需要点位名称与指引时按 id 读取；map-service 不可用时由
 * {@code RemoteService} 降级返回 null（前端 preferPointName 为可选字段）。
 */
@FeignClient(name = "ershou-map-service", contextId = "itemMapClient", path = "/inner/points")
public interface MapPointClient {

    @GetMapping("/{id}")
    Result<PointVO> get(@PathVariable("id") Long id);

    @GetMapping
    Result<List<PointVO>> list(@org.springframework.web.bind.annotation.RequestParam("campusId") Long campusId);
}
