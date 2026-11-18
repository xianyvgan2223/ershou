package com.ershou.cloud.map.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.map.dto.PointSuggestDTO;
import com.ershou.cloud.map.entity.TradePoint;
import com.ershou.cloud.map.service.TradePointService;
import com.ershou.cloud.map.vo.MapDataVO;
import com.ershou.cloud.map.vo.TradePointVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MapController {

    private final TradePointService tradePointService;

    @GetMapping("/map/points")
    public Result<MapDataVO> mapData(@RequestParam Long campusId) {
        return Result.ok(tradePointService.mapData(campusId));
    }

    @GetMapping("/trade-points")
    public Result<List<TradePointVO>> points(@RequestParam Long campusId) {
        return Result.ok(tradePointService.list(campusId));
    }

    @GetMapping("/trade-points/recommend")
    public Result<List<TradePointVO>> recommend(@RequestParam Long campusId,
                                                @RequestParam(required = false) Long itemId) {
        Long userId = UserContext.getUserId();
        return Result.ok(tradePointService.recommend(campusId, itemId, userId));
    }

    @GetMapping("/trade-points/nearby")
    public Result<List<Map<String, Object>>> nearby(@RequestParam Long campusId,
                                                    @RequestParam Double lng,
                                                    @RequestParam Double lat,
                                                    @RequestParam(required = false) Integer radius,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.ok(tradePointService.nearbyPoints(campusId, lng, lat, radius, limit));
    }

    @PostMapping("/trade-points/suggest")
    public Result<TradePointVO> suggest(@Valid @RequestBody PointSuggestDTO dto) {
        TradePoint point = tradePointService.suggest(dto, UserContext.requireUserId());
        return Result.ok(tradePointService.toVO(point));
    }

    @PostMapping("/trade-points")
    public Result<TradePointVO> createMine(@Valid @RequestBody PointSuggestDTO dto) {
        TradePoint point = tradePointService.createByUser(dto, UserContext.requireUserId());
        return Result.ok(tradePointService.toVO(point));
    }

    @PostMapping("/trade-points/search-hit")
    public Result<TradePointVO> saveSearchHit(@Valid @RequestBody PointSuggestDTO dto) {
        TradePoint point = tradePointService.saveSearchHit(dto, UserContext.requireUserId());
        return Result.ok(tradePointService.toVO(point));
    }

    @GetMapping("/trade-points/my-points")
    public Result<List<TradePointVO>> myPoints(@RequestParam Long campusId) {
        // 网关鉴权后注入 X-User-Id → UserContext；直连本服务未带该头时 requireUserId 抛 10002
        return Result.ok(tradePointService.listMineVO(campusId, UserContext.requireUserId()));
    }
}
