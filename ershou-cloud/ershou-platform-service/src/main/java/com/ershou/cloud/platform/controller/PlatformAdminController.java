package com.ershou.cloud.platform.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.service.PlatformAdminGuard;
import com.ershou.cloud.platform.dto.PlatformItemPublishDTO;
import com.ershou.cloud.platform.dto.PointSaveDTO;
import com.ershou.cloud.platform.service.PlatformCacheService;
import com.ershou.cloud.platform.service.PlatformCouponService;
import com.ershou.cloud.platform.service.PlatformFlashSaleService;
import com.ershou.cloud.platform.service.PlatformItemService;
import com.ershou.cloud.platform.service.PlatformPointService;
import com.ershou.cloud.platform.vo.PlatformItemVO;
import com.ershou.cloud.platform.vo.PlatformPointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 平台严选 · 管理后台接口（设计 3.4）。
 *
 * <p>网关已统一校验登录；每个接口内部再用 {@link PlatformAdminGuard#requireAdminId()} 校验运营身份
 * （与项目其它后台接口一致：登录 ≠ 管理员）。
 */
@Slf4j
@Tag(name = "平台严选（运营后台）")
@RestController
@RequestMapping("/api/v1/platform/admin")
@RequiredArgsConstructor
public class PlatformAdminController {

    private final PlatformAdminGuard adminGuard;
    private final PlatformPointService pointService;
    private final PlatformItemService itemService;
    private final PlatformCouponService couponService;
    private final PlatformFlashSaleService flashSaleService;
    private final PlatformCacheService cacheService;

    // ------------------------------------------------------------- 代卖点 CRUD

    @Operation(summary = "全部代卖点（含停用；campusId 不传=全部校区）")
    @GetMapping("/points")
    public Result<List<PlatformPointVO>> points(@RequestParam(required = false) Long campusId) {
        adminGuard.requireAdminId();
        return Result.ok(pointService.listAdmin(campusId));
    }

    @Operation(summary = "新建代卖点")
    @PostMapping("/points")
    public Result<PlatformPointVO> createPoint(@Valid @RequestBody PointSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(pointService.create(dto));
    }

    @Operation(summary = "编辑代卖点")
    @PutMapping("/points/{id}")
    public Result<PlatformPointVO> updatePoint(@PathVariable Long id, @Valid @RequestBody PointSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(pointService.update(id, dto));
    }

    @Operation(summary = "删除代卖点（软删 status=0，不破坏历史商品引用）")
    @DeleteMapping("/points/{id}")
    public Result<Void> deletePoint(@PathVariable Long id) {
        adminGuard.requireAdminId();
        pointService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "启停代卖点（status=0|1）")
    @PostMapping("/points/{id}/status")
    public Result<Void> updatePointStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminGuard.requireAdminId();
        pointService.updateStatus(id, status);
        return Result.ok();
    }

    // ------------------------------------------------------------- 平台商品发布

    @Operation(summary = "发布平台商品（免审核直接上架，同步 ES + 清缓存）")
    @PostMapping("/items")
    public Result<PlatformItemVO> publishItem(@Valid @RequestBody PlatformItemPublishDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(itemService.publish(dto));
    }

    // ------------------------------------------------------------- 缓存重建

    @Operation(summary = "重建券/秒杀 Redis 缓存（预热；返回重建的库存 key 数）")
    @PostMapping("/cache/refresh")
    public Result<Integer> refreshCache() {
        adminGuard.requireAdminId();
        Set<Long> campusIds = cacheService.knownCampusIds();
        int stockKeys = cacheService.refresh();
        // 设计 3.4：重建后 4 类业务缓存也应立刻可用（不要等用户第一次请求才回填）。
        // 这里主动读一遍走 JsonCache 的"未命中→回源→回填"，与正常运行时的缓存内容完全一致。
        for (Long campusId : campusIds) {
            try {
                pointService.listPublic(campusId);
                itemService.overview(campusId);
                couponService.listPublic(campusId, null);
                flashSaleService.current(campusId, null);
            } catch (Exception e) {
                log.warn("[platform][refresh] 校区 {} 业务缓存重建失败（TTL 内会按需回源）：{}", campusId, e.getMessage());
            }
        }
        log.info("[platform][refresh] 缓存重建完成：库存 key {} 个，业务缓存校区 {}", stockKeys, campusIds);
        return Result.ok(stockKeys);
    }
}
