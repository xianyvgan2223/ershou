package com.ershou.cloud.item.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.item.review.ItemReviewService;
import com.ershou.cloud.item.review.ItemReviewVO;
import com.ershou.cloud.item.review.ReviewOfflineDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 上架审核队列内部接口（T102 契约 §六：admin-service 依赖）。
 *
 * <p>item_review 归属 ershou_item 库，admin-service 经本组接口读写；本服务不写 sys_audit_log
 * （归 ershou_admin，由 admin-service 记录操作日志）。
 *
 * <p>动作语义（与 admin 页面对齐）：
 * <ul>
 *   <li>{@code approve}：确认看过无问题；若商品在「审核中(1)」则同时放行到在售(2)；</li>
 *   <li>{@code reject}：审核中(1)→审核未通过(6)；其余可撤状态→已下架(5)并作废挂单；</li>
 *   <li>{@code /offline}：审核页下架 → 已下架(5)，卖家可改后重新上架。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/inner/item-reviews")
@RequiredArgsConstructor
public class InnerItemReviewController {

    private final ItemReviewService itemReviewService;

    /** 审核队列分页：status=pending|done|0|1（空=全部） */
    @GetMapping
    public Result<PageResult<ItemReviewVO>> page(@RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(itemReviewService.page(status, keyword, page, pageSize));
    }

    /** 审核统计：{pending,todayApproved,todayRejected}（兼容旧字段 reviewedToday/onSaleTotal） */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(itemReviewService.statsMap());
    }

    /** 单条审核详情；不存在返回 code=30001 */
    @GetMapping("/{itemId}")
    public Result<ItemReviewVO> detail(@PathVariable Long itemId) {
        ItemReviewVO vo = itemReviewService.detail(itemId);
        if (vo == null) {
            return Result.fail(30001, "该商品没有审核记录");
        }
        return Result.ok(vo);
    }

    /** 处置单条：body {action:approve|reject, remark} */
    @PostMapping("/{itemId}/reviewed")
    public Result<Map<String, Object>> reviewed(@PathVariable Long itemId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        String action = body == null ? "approve" : String.valueOf(body.getOrDefault("action", "approve"));
        String remark = body == null || body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        Long operatorId = UserContext.getUserId();
        if ("reject".equalsIgnoreCase(action)) {
            return Result.ok(itemReviewService.reject(itemId, remark, operatorId));
        }
        return Result.ok(itemReviewService.approve(itemId, remark, operatorId));
    }

    /** 审核页下架：body {reason} */
    @PostMapping("/{itemId}/offline")
    public Result<Map<String, Object>> offline(@PathVariable Long itemId,
                                               @RequestBody(required = false) ReviewOfflineDTO dto) {
        String reason = dto == null ? null : dto.getReason();
        return Result.ok(itemReviewService.downlist(itemId, reason, UserContext.getUserId()));
    }

    /** 批量处置：body {itemIds:[], action:approve|reject, remark} */
    @PostMapping("/batch")
    public Result<Map<String, Object>> batch(@RequestBody Map<String, Object> body) {
        List<Long> itemIds = new ArrayList<>();
        Object rawIds = body == null ? null : body.get("itemIds");
        if (rawIds instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) {
                    itemIds.add(n.longValue());
                } else if (o != null) {
                    itemIds.add(Long.valueOf(String.valueOf(o)));
                }
            }
        }
        String action = body == null ? "approve" : String.valueOf(body.getOrDefault("action", "approve"));
        String remark = body == null || body.get("remark") == null ? null : String.valueOf(body.get("remark"));
        return Result.ok(itemReviewService.batch(itemIds, action, remark, UserContext.getUserId()));
    }

    /** 存量补登记（重建审核队列） */
    @PostMapping("/backfill")
    public Result<Integer> backfill(@RequestParam(defaultValue = "500") int limit) {
        return Result.ok(itemReviewService.backfillMissing(limit));
    }
}
