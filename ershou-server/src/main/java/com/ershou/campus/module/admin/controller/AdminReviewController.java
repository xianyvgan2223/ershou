package com.ershou.campus.module.admin.controller;

import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.admin.dto.BatchReviewActionDTO;
import com.ershou.campus.module.admin.dto.ReviewOfflineDTO;
import com.ershou.campus.module.admin.service.AdminAuthService;
import com.ershou.campus.module.admin.service.ItemReviewService;
import com.ershou.campus.module.admin.vo.BatchAuditResultVO;
import com.ershou.campus.module.admin.vo.ItemReviewStatsVO;
import com.ershou.campus.module.admin.vo.ItemReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 运营后台 · 上架审核。
 *
 * <p>产品口径：商品上架<b>不拦截</b>（发布即在售），但这个页面要保证
 * "每一条上架到在售的商品都被审核员看过" —— 所以每条上架/改名都会进这个队列，
 * 审核员点「已审核」后归档消失，觉得不合适可以直接「下架」。
 *
 * <p>实时性：{@code ws://host/ws/admin/review?token=xxx}（见 AdminReviewWebSocketHandler），
 * 新上架 / 被处置都会在 1 秒内推到所有开着这个页面的审核员。
 */
@Tag(name = "运营后台 · 上架审核")
@RestController
@RequestMapping("/api/v1/admin/review")
@RequiredArgsConstructor
public class AdminReviewController {

    private final AdminAuthService adminAuthService;
    private final ItemReviewService reviewService;

    @Operation(summary = "上架审核列表（scope=pending|done|all）")
    @GetMapping("/feed")
    public Result<PageResult<ItemReviewVO>> feed(@RequestParam(required = false) String scope,
                                                 @RequestParam(required = false) String sellerId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(reviewService.page(scope, parseId(sellerId), keyword, pageNum, pageSize));
    }

    /**
     * 卖家 id 用字符串接再自己解析。
     *
     * <p>写成 {@code Long sellerId} 时，前端传个非数字（运营手输筛选框很正常，
     * 比如刚敲了一个字母）会抛 MethodArgumentTypeMismatchException，
     * 全局异常处理把它兜成 90001「系统开小差」—— 其实只是输入不合法，
     * 而且整个列表都出不来了。这里按"解析不了就当没筛"处理，列表照常返回。
     */
    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Operation(summary = "上架审核统计（待审 / 今日新上架 / 今日已审 / 在线审核员）")
    @GetMapping("/stats")
    public Result<ItemReviewStatsVO> stats() {
        adminAuthService.requireAdminId();
        return Result.ok(reviewService.stats());
    }

    @Operation(summary = "单条详情（含完整描述与图片）")
    @GetMapping("/items/{itemId}")
    public Result<ItemReviewVO> detail(@PathVariable Long itemId) {
        adminAuthService.requireAdminId();
        return Result.ok(reviewService.detail(itemId));
    }

    @Operation(summary = "已审核（确认看过，无问题）→ 从待审列表归档")
    @PostMapping("/items/{itemId}/reviewed")
    public Result<ItemReviewStatsVO> reviewed(@PathVariable Long itemId,
                                              @RequestBody(required = false) ReviewOfflineDTO dto) {
        Long operatorId = adminAuthService.requireAdminId();
        return Result.ok(reviewService.markReviewed(itemId, dto == null ? null : dto.getReason(), operatorId));
    }

    @Operation(summary = "下架（内容不合适，撤下来；卖家改好可重新上架）")
    @PostMapping("/items/{itemId}/offline")
    public Result<ItemReviewStatsVO> offline(@PathVariable Long itemId,
                                             @RequestBody(required = false) ReviewOfflineDTO dto) {
        Long operatorId = adminAuthService.requireAdminId();
        return Result.ok(reviewService.downlist(itemId, dto == null ? null : dto.getReason(), operatorId));
    }

    @Operation(summary = "批量处置（action=review 已审核 / action=offline 下架）")
    @PostMapping("/batch/{action}")
    public Result<BatchAuditResultVO> batch(@PathVariable String action,
                                            @Valid @RequestBody BatchReviewActionDTO dto) {
        Long operatorId = adminAuthService.requireAdminId();
        return Result.ok(reviewService.batch(dto, action, operatorId));
    }

    @Operation(summary = "补登记：把在售却没有审核记录的商品补进队列（运营手动兜底）")
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "500") int limit) {
        adminAuthService.requireAdminId();
        return Result.ok(reviewService.backfillMissing(limit));
    }
}
