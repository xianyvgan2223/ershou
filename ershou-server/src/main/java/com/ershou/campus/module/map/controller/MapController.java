package com.ershou.campus.module.map.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.map.dto.PointSuggestDTO;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.map.vo.MapDataVO;
import com.ershou.campus.module.map.vo.TradePointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "校内地图")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MapController {

    private final TradePointService tradePointService;

    @Operation(summary = "地图数据：底图 + 点位 + 每点位在售数")
    @GetMapping("/map/points")
    public Result<MapDataVO> mapData(@RequestParam Long campusId) {
        return Result.ok(tradePointService.mapData(campusId));
    }

    @Operation(summary = "点位列表")
    @GetMapping("/trade-points")
    public Result<List<TradePointVO>> points(@RequestParam Long campusId) {
        return Result.ok(tradePointService.list(campusId));
    }

    @Operation(summary = "面交点推荐 Top5")
    @GetMapping("/trade-points/recommend")
    public Result<List<TradePointVO>> recommend(@RequestParam Long campusId,
                                                @RequestParam(required = false) Long itemId) {
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return Result.ok(tradePointService.recommend(campusId, itemId, userId));
    }

    @Operation(summary = "用户自定义点位提交（异步审核）")
    @PostMapping("/trade-points/suggest")
    public Result<Void> suggest(@Valid @RequestBody PointSuggestDTO dto) {
        StpUtil.checkLogin();
        tradePointService.suggest(dto, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }
}
