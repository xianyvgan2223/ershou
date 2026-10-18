package com.ershou.campus.module.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.platform.service.PlatformCouponService;
import com.ershou.campus.module.platform.service.PlatformFlashSaleService;
import com.ershou.campus.module.platform.service.PlatformItemService;
import com.ershou.campus.module.platform.service.PlatformPointService;
import com.ershou.campus.module.platform.vo.CouponVO;
import com.ershou.campus.module.platform.vo.FlashSaleVO;
import com.ershou.campus.module.platform.vo.PlatformItemVO;
import com.ershou.campus.module.platform.vo.PlatformOverviewVO;
import com.ershou.campus.module.platform.vo.PlatformPointVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台严选 · 公开读接口（设计 3.2）。
 *
 * <p>整条 {@code /api/v1/platform/shop/**} 在 {@code SaTokenConfig} 白名单里，<b>不需要登录</b>：
 * 首页/专区游客也能看。券列表里的 claimed 字段在有登录态时按当前用户算，未登录恒 false。
 */
@Tag(name = "平台严选（公开）")
@RestController
@RequestMapping("/api/v1/platform/shop")
@RequiredArgsConstructor
public class PlatformShopController {

    private final PlatformItemService itemService;
    private final PlatformPointService pointService;
    private final PlatformCouponService couponService;
    private final PlatformFlashSaleService flashSaleService;

    @Operation(summary = "平台专区概览")
    @GetMapping("/overview")
    public Result<PlatformOverviewVO> overview(@RequestParam(defaultValue = "2") Long campusId) {
        return Result.ok(itemService.overview(campusId));
    }

    @Operation(summary = "代卖/自提点列表（只返回启用中，按 sort,id）")
    @GetMapping("/points")
    public Result<List<PlatformPointVO>> points(@RequestParam(defaultValue = "2") Long campusId) {
        return Result.ok(pointService.listPublic(campusId));
    }

    @Operation(summary = "平台商品分页（只返回 seller_type=2 且 status=2）")
    @GetMapping("/items")
    public Result<PageResult<PlatformItemVO>> items(@RequestParam(defaultValue = "2") Long campusId,
                                                    @RequestParam(required = false) Long pointId,
                                                    @RequestParam(required = false) Long categoryId,
                                                    @RequestParam(defaultValue = "latest") String sort,
                                                    @RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "12") long pageSize) {
        return Result.ok(itemService.page(campusId, pointId, categoryId, sort, pageNum, pageSize));
    }

    @Operation(summary = "可领优惠券列表（含实时剩余库存与当前用户是否已领）")
    @GetMapping("/coupons")
    public Result<List<CouponVO>> coupons(@RequestParam(defaultValue = "2") Long campusId) {
        return Result.ok(couponService.listPublic(campusId, currentUserIdOrNull()));
    }

    @Operation(summary = "当前进行中的秒杀场次（无场次时 data 为空）")
    @GetMapping("/flash-sale")
    public Result<FlashSaleVO> flashSale(@RequestParam(defaultValue = "2") Long campusId) {
        return Result.ok(flashSaleService.current(campusId, currentUserIdOrNull()));
    }

    /** 公开接口允许游客访问：未登录返回 null，而不是抛 NotLoginException */
    private Long currentUserIdOrNull() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
    }
}
