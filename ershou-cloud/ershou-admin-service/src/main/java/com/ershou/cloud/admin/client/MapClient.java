package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * map-service 内部接口：面交点审核/启停/清理/统计（trade_point 域）。
 *
 * <p>与 map-member（T105）实际实现对齐（2026-11-13）：
 * <ul>
 *   <li>{@code GET /inner/points?campusId=&status=} 返回**全量列表**（无分页/无 auditStatus/keyword 参数）
 *       → 管理端的关键词/审核状态筛选与分页在 admin-service 本地做（数据量小，演示可接受）；</li>
 *   <li>{@code POST /inner/points/{id}/audit?action=approve|reject} 只收 action（不接收安全分/指引/驳回原因）
 *       → 这些附加字段由 admin-service 记审计日志，跨服务落库需 map-service 扩展，已列入遗留；</li>
 *   <li>{@code POST /inner/points/{id}/status?status=} 只收 status（reason 由 admin 侧审计留痕）。</li>
 * </ul>
 */
@FeignClient(name = "ershou-map-service", contextId = "adminMapClient", path = "/inner",
        fallback = MapClientFallback.class)
public interface MapClient {

    /** 点位列表（全量；campusId/status 可选，服务端过滤） */
    @GetMapping("/points")
    Result<List<Map<String, Object>>> listPoints(@RequestParam("campusId") Long campusId,
                                                 @RequestParam("status") Integer status);

    @GetMapping("/points/{id}")
    Result<Map<String, Object>> getPoint(@PathVariable("id") Long id);

    /** 点位统计：{total,pending,disabled} */
    @GetMapping("/points/stats")
    Result<Map<String, Object>> pointStats();

    /** 审核：action=approve|reject（附加校准字段暂不由 map 接收） */
    @PostMapping("/points/{id}/audit")
    Result<Object> auditPoint(@PathVariable("id") Long id, @RequestParam("action") String action);

    /** 启用/停用 */
    @PostMapping("/points/{id}/status")
    Result<Object> updatePointStatus(@PathVariable("id") Long id, @RequestParam("status") Integer status);

    /** 清理没有商品的面交点（立即执行；日常由 map-service 定时任务 03:10 跑） */
    @PostMapping("/points/cleanup-empty")
    Result<Integer> cleanupEmptyPoints();
}
