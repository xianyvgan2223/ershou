package com.ershou.cloud.platform.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.service.PlatformItemService;
import com.ershou.cloud.platform.service.PlatformPointService;
import com.ershou.cloud.platform.vo.PlatformOverviewVO;
import com.ershou.cloud.platform.vo.PlatformPointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台服务内部接口（全量拆分契约，最小集）。
 *
 * <p>不经过网关，供 admin-service / chat-service 等内部调用：
 * <ul>
 *   <li>{@code GET /inner/platform/points?campusId=} → 平台代卖点列表（管理端/运营台复用）；</li>
 *   <li>{@code GET /inner/platform/summary?campusId=} → 平台数据概览（在售商品数/点位数/可领券/秒杀场次）。</li>
 * </ul>
 */
@Tag(name = "平台服务（内部）")
@RestController
@RequestMapping("/inner/platform")
@RequiredArgsConstructor
public class InnerPlatformController {

    private final PlatformPointService pointService;
    private final PlatformItemService itemService;

    @Operation(summary = "平台代卖点列表（含停用，管理端用）")
    @GetMapping("/points")
    public Result<List<PlatformPointVO>> points(@RequestParam(required = false) Long campusId) {
        return Result.ok(pointService.listAdmin(campusId));
    }

    @Operation(summary = "平台数据概览（在售平台商品数/点位数/可领券数/当前秒杀场次）")
    @GetMapping("/summary")
    public Result<PlatformOverviewVO> summary(@RequestParam(required = false) Long campusId) {
        return Result.ok(itemService.overview(campusId));
    }
}
