package com.ershou.cloud.platform.controller;

import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.dto.PlatformBuyDTO;
import com.ershou.cloud.platform.service.PlatformCouponService;
import com.ershou.cloud.platform.service.PlatformFlashSaleService;
import com.ershou.cloud.platform.service.PlatformItemService;
import com.ershou.cloud.platform.vo.CouponClaimVO;
import com.ershou.cloud.platform.vo.GrabVO;
import com.ershou.cloud.platform.vo.MyCouponVO;
import com.ershou.cloud.platform.vo.PlatformBuyVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台严选 · 登录用户动作接口（设计 3.3）。
 *
 * <p>这些路径不在 SaTokenConfig 白名单里，Sa-Token 默认拦截 → 未登录统一由
 * GlobalExceptionHandler 返回"未登录"业务码。抢券/抢购失败（抢完、超限、限流等）
 * 仍返回 HTTP 200 + 业务子码，由前端按 {@code code} 给文案。
 */
@Tag(name = "平台严选（当前用户）")
@RestController
@RequestMapping("/api/v1/platform/me")
@RequiredArgsConstructor
public class PlatformMeController {

    private final PlatformCouponService couponService;
    private final PlatformFlashSaleService flashSaleService;
    private final PlatformItemService itemService;

    @Operation(summary = "抢券（Lua 原子扣减，失败也返回 200 + 子码）")
    @PostMapping("/coupons/{id}/claim")
    public Result<CouponClaimVO> claim(@PathVariable Long id) {
        return Result.ok(couponService.claim(id, UserContext.requireUserId()));
    }

    @Operation(summary = "我的券列表（DB 为准，含状态）")
    @GetMapping("/coupons")
    public Result<List<MyCouponVO>> myCoupons() {
        return Result.ok(couponService.myCoupons(UserContext.requireUserId()));
    }

    @Operation(summary = "秒杀抢购（成功即生成平台自提单；失败按子码提示）")
    @PostMapping("/flash-sale/{itemId}/grab")
    public Result<GrabVO> grab(@PathVariable Long itemId) {
        return Result.ok(flashSaleService.grab(itemId, UserContext.requireUserId()));
    }

    @Operation(summary = "平台商品直购（couponId 本轮不抵扣，仅预留）")
    @PostMapping("/items/{id}/buy")
    public Result<PlatformBuyVO> buy(@PathVariable Long id,
                                     @RequestBody(required = false) PlatformBuyDTO dto) {
        return Result.ok(itemService.buy(id, UserContext.requireUserId(), dto));
    }
}
