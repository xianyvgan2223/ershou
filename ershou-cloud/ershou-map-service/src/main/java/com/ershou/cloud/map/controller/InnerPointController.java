package com.ershou.cloud.map.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.map.entity.TradePoint;
import com.ershou.cloud.map.service.ItemStatService;
import com.ershou.cloud.map.service.TradePointService;
import com.ershou.cloud.map.vo.InnerPointVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 地图服务内部接口（**不经过网关**，供其它微服务 Feign 调用）。
 *
 * <p>契约：{@code ershou-cloud/docs/full-split-contract.md} 第三/六节：
 * <ul>
 *   <li>{@code GET /inner/points/{id}} → 点位详情（最小字段 id/name/guide/longitude/latitude/campusId）；</li>
 *   <li>{@code GET /inner/points?campusId=&status=} → 列表（campusId/status 均可选，admin 管理列表用）；</li>
 *   <li>{@code GET /inner/points/stats} → {@code {total,pending,disabled}}（admin 概览）；</li>
 *   <li>{@code POST /inner/points/{id}/audit?action=approve|reject} → 点位审核；</li>
 *   <li>{@code POST /inner/points/{id}/status?status=} → 启停；</li>
 *   <li>{@code POST /inner/points/cleanup-empty} → 立即清理空点位；</li>
 *   <li>{@code GET /inner/points/{id}/items?page=&pageSize=} → 点位商品列表（透传 item-service，供拼装用）。</li>
 * </ul>
 *
 * <p>鉴权：内部接口不经网关，按契约由调用方（如 admin-service）做管理员校验；本服务只保证"不暴露到网关路由"。
 */
@RestController
@RequestMapping("/inner/points")
@RequiredArgsConstructor
public class InnerPointController {

    private final TradePointService tradePointService;
    private final ItemStatService itemStatService;

    @GetMapping("/{id}")
    public Result<InnerPointVO> detail(@PathVariable Long id) {
        return Result.ok(tradePointService.innerDetail(id));
    }

    @GetMapping
    public Result<List<InnerPointVO>> list(@RequestParam(required = false) Long campusId,
                                           @RequestParam(required = false) Integer status) {
        return Result.ok(tradePointService.innerList(campusId, status));
    }

    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(tradePointService.innerStats());
    }

    @PostMapping("/{id}/audit")
    public Result<InnerPointVO> audit(@PathVariable Long id,
                                      @RequestParam String action) {
        TradePoint p = tradePointService.audit(id, action);
        return Result.ok(tradePointService.innerDetail(p.getId()));
    }

    @PostMapping("/{id}/status")
    public Result<InnerPointVO> status(@PathVariable Long id,
                                       @RequestParam Integer status) {
        TradePoint p = tradePointService.updateStatus(id, status);
        return Result.ok(tradePointService.innerDetail(p.getId()));
    }

    @PostMapping("/cleanup-empty")
    public Result<Integer> cleanupEmpty() {
        return Result.ok(tradePointService.cleanupEmptyPoints());
    }

    @GetMapping("/{id}/items")
    public Result<Object> items(@PathVariable Long id,
                                @RequestParam(defaultValue = "1") long page,
                                @RequestParam(defaultValue = "20") long pageSize) {
        TradePoint p = tradePointService.findByIdOrNull(id);
        if (p == null || p.getCampusId() == null) {
            return Result.fail(com.ershou.cloud.common.result.ResultCode.PARAM_ERROR.getCode(), "面交点不存在");
        }
        return itemStatService.itemsByPoint(p.getCampusId(), id, page, pageSize);
    }
}
