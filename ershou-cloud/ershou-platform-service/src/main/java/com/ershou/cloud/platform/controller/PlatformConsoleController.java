package com.ershou.cloud.platform.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.service.PlatformAdminGuard;
import com.ershou.cloud.platform.dto.AdminItemUpdateDTO;
import com.ershou.cloud.platform.dto.CouponSaveDTO;
import com.ershou.cloud.platform.dto.FlashSaleItemSaveDTO;
import com.ershou.cloud.platform.dto.FlashSaleSaveDTO;
import com.ershou.cloud.platform.service.PlatformConsoleService;
import com.ershou.cloud.platform.service.PlatformCouponService;
import com.ershou.cloud.platform.service.PlatformFlashSaleService;
import com.ershou.cloud.platform.service.PlatformItemService;
import com.ershou.cloud.platform.vo.AdminCouponVO;
import com.ershou.cloud.platform.vo.AdminFlashSaleVO;
import com.ershou.cloud.platform.vo.AdminPlatformItemVO;
import com.ershou.cloud.platform.vo.AdminPlatformOrderVO;
import com.ershou.cloud.platform.vo.PlatformAdminOverviewVO;
import com.ershou.cloud.platform.vo.PlatformCacheStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/**
 * 平台运营台 · 管理接口（第 3 轮设计 3.1–3.6）。
 *
 * <p>与 {@link PlatformAdminController}（第 2 轮：点位 CRUD / 发布商品 / 刷新缓存）同前缀，
 * 这里只补运营台新增能力：概览、平台商品管理、券管理、秒杀场次管理、平台订单、缓存统计。
 * 拆成两个 controller 是为了单文件别太大、职责清晰；两条 controller 的 method+path 不冲突。
 *
 * <p><b>每个接口第一行都调 {@link PlatformAdminGuard#requireAdminId()}</b>：
 * Sa-Token 只保证"登录"，运营身份必须逐个校验（与项目其它后台接口一致）。
 */
@Tag(name = "平台运营台（管理）")
@RestController
@RequestMapping("/api/v1/platform/admin")
@RequiredArgsConstructor
public class PlatformConsoleController {

    private final PlatformAdminGuard adminGuard;
    private final PlatformConsoleService consoleService;
    private final PlatformItemService itemService;
    private final PlatformCouponService couponService;
    private final PlatformFlashSaleService flashSaleService;

    // ---------------------------------------------------------------- 3.1 概览

    @Operation(summary = "运营台概览统计（商品/点位/券/秒杀/订单/GMV/库存告警）")
    @GetMapping("/overview")
    public Result<PlatformAdminOverviewVO> overview(@RequestParam(required = false) Long campusId) {
        adminGuard.requireAdminId();
        return Result.ok(consoleService.overview(campusId));
    }

    // ---------------------------------------------------------------- 3.2 平台商品

    @Operation(summary = "平台商品分页（含全部状态；支持关键词/状态/代卖点筛选）")
    @GetMapping("/items")
    public Result<PageResult<AdminPlatformItemVO>> items(@RequestParam(defaultValue = "1") long pageNum,
                                                         @RequestParam(defaultValue = "10") long pageSize,
                                                         @RequestParam(required = false) Integer status,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) Long campusId,
                                                         @RequestParam(required = false) Long pointId) {
        adminGuard.requireAdminId();
        return Result.ok(itemService.adminPage(campusId, pointId, status, keyword, pageNum, pageSize));
    }

    @Operation(summary = "编辑平台商品（标题/价格/库存/代卖点）")
    @PutMapping("/items/{id}")
    public Result<AdminPlatformItemVO> updateItem(@PathVariable Long id,
                                                  @Valid @RequestBody AdminItemUpdateDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(itemService.adminUpdate(id, dto));
    }

    @Operation(summary = "下架平台商品（status=5）")
    @PostMapping("/items/{id}/offline")
    public Result<Void> offlineItem(@PathVariable Long id) {
        adminGuard.requireAdminId();
        itemService.adminOffline(id);
        return Result.ok();
    }

    @Operation(summary = "重新上架平台商品（status=2、有效期 +30 天）")
    @PostMapping("/items/{id}/online")
    public Result<Void> onlineItem(@PathVariable Long id) {
        adminGuard.requireAdminId();
        itemService.adminOnline(id);
        return Result.ok();
    }

    // ---------------------------------------------------------------- 3.3 优惠券

    @Operation(summary = "运营台券列表（含已领数与实时剩余）")
    @GetMapping("/coupons")
    public Result<List<AdminCouponVO>> coupons(@RequestParam(required = false) Long campusId) {
        adminGuard.requireAdminId();
        return Result.ok(couponService.adminList(campusId));
    }

    @Operation(summary = "新建优惠券")
    @PostMapping("/coupons")
    public Result<AdminCouponVO> createCoupon(@Valid @RequestBody CouponSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(couponService.create(dto));
    }

    @Operation(summary = "编辑优惠券（已发放数 > 新总库存时拒绝）")
    @PutMapping("/coupons/{id}")
    public Result<AdminCouponVO> updateCoupon(@PathVariable Long id,
                                              @Valid @RequestBody CouponSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(couponService.update(id, dto));
    }

    @Operation(summary = "券启停（status=0|1）")
    @PostMapping("/coupons/{id}/status")
    public Result<Void> couponStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminGuard.requireAdminId();
        couponService.changeStatus(id, status);
        return Result.ok();
    }

    // ---------------------------------------------------------------- 3.4 秒杀场次

    @Operation(summary = "运营台秒杀场次列表（含商品明细与实时剩余）")
    @GetMapping("/flash-sales")
    public Result<List<AdminFlashSaleVO>> flashSales(@RequestParam(required = false) Long campusId) {
        adminGuard.requireAdminId();
        return Result.ok(flashSaleService.adminList(campusId));
    }

    @Operation(summary = "新建秒杀场次")
    @PostMapping("/flash-sales")
    public Result<AdminFlashSaleVO> createFlashSale(@Valid @RequestBody FlashSaleSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(flashSaleService.create(dto));
    }

    @Operation(summary = "编辑秒杀场次（时间/状态/排序）")
    @PutMapping("/flash-sales/{id}")
    public Result<AdminFlashSaleVO> updateFlashSale(@PathVariable Long id,
                                                    @Valid @RequestBody FlashSaleSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(flashSaleService.update(id, dto));
    }

    @Operation(summary = "场次添加秒杀商品（校验是平台商品）")
    @PostMapping("/flash-sales/{id}/items")
    public Result<AdminFlashSaleVO> addFlashItem(@PathVariable Long id,
                                                 @Valid @RequestBody FlashSaleItemSaveDTO dto) {
        adminGuard.requireAdminId();
        return Result.ok(flashSaleService.addItem(id, dto));
    }

    @Operation(summary = "场次移除秒杀商品")
    @DeleteMapping("/flash-sales/{id}/items/{itemId}")
    public Result<Void> removeFlashItem(@PathVariable Long id, @PathVariable Long itemId) {
        adminGuard.requireAdminId();
        flashSaleService.removeItem(id, itemId);
        return Result.ok();
    }

    @Operation(summary = "场次切状态（1 预告 / 2 进行中 / 3 结束）")
    @PostMapping("/flash-sales/{id}/status")
    public Result<Void> flashStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminGuard.requireAdminId();
        flashSaleService.changeStatus(id, status);
        return Result.ok();
    }

    // ---------------------------------------------------------------- 3.5 平台订单

    @Operation(summary = "平台订单分页（只含 seller_id=平台账号，联买家昵称与平台点名）")
    @GetMapping("/orders")
    public Result<PageResult<AdminPlatformOrderVO>> orders(@RequestParam(defaultValue = "1") long pageNum,
                                                           @RequestParam(defaultValue = "10") long pageSize,
                                                           @RequestParam(required = false) Integer status,
                                                           @RequestParam(required = false) String keyword) {
        adminGuard.requireAdminId();
        return Result.ok(consoleService.orders(status, keyword, pageNum, pageSize));
    }

    // ---------------------------------------------------------------- 3.6 缓存运维

    @Operation(summary = "Redis 缓存统计（只读 SCAN ershou:platform:*）")
    @GetMapping("/cache/stats")
    public Result<PlatformCacheStatsVO> cacheStats() {
        adminGuard.requireAdminId();
        return Result.ok(consoleService.cacheStats());
    }
}
