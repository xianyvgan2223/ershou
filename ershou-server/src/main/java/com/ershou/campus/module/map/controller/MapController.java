package com.ershou.campus.module.map.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.map.dto.PointSuggestDTO;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.map.vo.MapDataVO;
import com.ershou.campus.module.map.vo.TradePointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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

    @Operation(summary = "我附近的点位（Redis GEO 检索，不消耗高德配额；坐标需为 GCJ-02）")
    @GetMapping("/trade-points/nearby")
    public Result<List<Map<String, Object>>> nearby(@RequestParam Long campusId,
                                                    @RequestParam Double lng,
                                                    @RequestParam Double lat,
                                                    @RequestParam(required = false) Integer radius,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.ok(tradePointService.nearbyPoints(campusId, lng, lat, radius, limit));
    }

    @Operation(summary = "用户自定义点位提交（免审核；返回点位本身，前端可直接插进列表）")
    @PostMapping("/trade-points/suggest")
    public Result<TradePointVO> suggest(@Valid @RequestBody PointSuggestDTO dto) {
        StpUtil.checkLogin();
        TradePoint point = tradePointService.suggest(dto, StpUtil.getLoginIdAsLong());
        return Result.ok(tradePointService.toVO(point));
    }

    @Operation(summary = "用户在地图上新增面交点（发布页用，每次新建、不做同名合并）")
    @PostMapping("/trade-points")
    public Result<TradePointVO> createMine(@Valid @RequestBody PointSuggestDTO dto) {
        StpUtil.checkLogin();
        TradePoint point = tradePointService.createByUser(dto, StpUtil.getLoginIdAsLong());
        return Result.ok(tradePointService.toVO(point));
    }

    @Operation(summary = "高德搜索到的地点落库（source=3；库内已有同名则直接复用，不再调用高德）")
    @PostMapping("/trade-points/search-hit")
    public Result<TradePointVO> saveSearchHit(@Valid @RequestBody PointSuggestDTO dto) {
        StpUtil.checkLogin();
        TradePoint point = tradePointService.saveSearchHit(dto, StpUtil.getLoginIdAsLong());
        return Result.ok(tradePointService.toVO(point));
    }

    @Operation(summary = "我提交过的点位（含待审，仅自己可见）")
    @GetMapping("/trade-points/my-points")
    public Result<List<TradePointVO>> myPoints(@RequestParam Long campusId) {
        StpUtil.checkLogin();
        return Result.ok(tradePointService.listMineVO(campusId, StpUtil.getLoginIdAsLong()));
    }
}
