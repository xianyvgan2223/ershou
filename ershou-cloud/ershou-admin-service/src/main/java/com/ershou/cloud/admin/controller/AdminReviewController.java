package com.ershou.cloud.admin.controller;

import com.ershou.cloud.admin.dto.BatchReviewActionDTO;
import com.ershou.cloud.admin.dto.ReviewOfflineDTO;
import com.ershou.cloud.admin.service.AdminAuthService;
import com.ershou.cloud.admin.service.AdminReviewService;
import com.ershou.cloud.admin.vo.BatchAuditResultVO;
import com.ershou.cloud.admin.vo.ItemReviewStatsVO;
import com.ershou.cloud.admin.vo.ItemReviewVO;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 上架审核（路径/请求/响应与旧单体 AdminReviewController 完全一致）。
 *
 * <p>item_review 数据在 item-service（ershou_item），这里只做审核员视角编排 + 审计留痕。
 */
@RestController
@RequestMapping("/api/v1/admin/review")
@RequiredArgsConstructor
public class AdminReviewController {

    private final AdminAuthService adminAuthService;
    private final AdminReviewService reviewService;

    /** 上架审核列表（scope=pending|done|all） */
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
     * 卖家 id 用字符串接再自己解析：运营手输筛选框里敲了非数字时，按"没筛"处理而不是 90001。
     * （与旧单体 AdminReviewController.parseId 行为一致）
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

    /** 上架审核统计（待审 / 今日已审 / 在线审核员） */
    @GetMapping("/stats")
    public Result<ItemReviewStatsVO> stats() {
        adminAuthService.requireAdminId();
        return Result.ok(reviewService.stats());
    }

    /** 单条详情（含完整描述与图片） */
    @GetMapping("/items/{itemId}")
    public Result<ItemReviewVO> detail(@PathVariable Long itemId) {
        adminAuthService.requireAdminId();
        return Result.ok(reviewService.detail(itemId));
    }

    /** 已审核（确认看过，无问题）→ 从待审列表归档 */
    @PostMapping("/items/{itemId}/reviewed")
    public Result<ItemReviewStatsVO> reviewed(@PathVariable Long itemId,
                                              @RequestBody(required = false) ReviewOfflineDTO dto) {
        Long operatorId = adminAuthService.requireAdminId();
        return Result.ok(reviewService.markReviewed(itemId, dto == null ? null : dto.getReason(), operatorId));
    }

    /** 下架（内容不合适，撤下来；卖家改好可重新上架） */
    @PostMapping("/items/{itemId}/offline")
    public Result<ItemReviewStatsVO> offline(@PathVariable Long itemId,
                                             @RequestBody(required = false) ReviewOfflineDTO dto) {
        Long operatorId = adminAuthService.requireAdminId();
        return Result.ok(reviewService.downlist(itemId, dto == null ? null : dto.getReason(), operatorId));
    }

    /** 批量处置（action=review 已审核 / action=offline 下架） */
    @PostMapping("/batch/{action}")
    public Result<BatchAuditResultVO> batch(@PathVariable String action,
                                            @Valid @RequestBody BatchReviewActionDTO dto) {
        Long operatorId = adminAuthService.requireAdminId();
        return Result.ok(reviewService.batch(dto, action, operatorId));
    }

    /** 补登记：把在售却没有审核记录的商品补进队列（运营手动兜底） */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "500") int limit) {
        return Result.ok(reviewService.backfill(limit, adminAuthService.requireAdminId()));
    }
}
