package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * item-service 内部接口：商品管理（审核/强制下架/列表）+ 上架审核队列 + 统计。
 *
 * <p>审核队列 {@code /inner/item-reviews/**} 按 full-split-contract 第六节；
 * 商品管理列表 {@code GET /inner/items}（全状态/关键词/卖家筛选）与 stats 为 T107 追加需求
 * （契约第六节已含 stats；通用分页列表待 item-service 补，见交付报告"遗留"）。
 */
@FeignClient(name = "ershou-item-service", contextId = "adminItemClient", path = "/inner",
        fallback = ItemClientFallback.class)
public interface ItemClient {

    /** 单商品详情 */
    @GetMapping("/items/{id}")
    Result<Map<String, Object>> getItem(@PathVariable("id") Long id);

    /** 批量商品 */
    @GetMapping("/items/batch")
    Result<List<Map<String, Object>>> batchItems(@RequestParam("ids") String ids);

    /** 商品管理分页（全状态；keyword 标题，sellerId 卖家筛选） */
    @GetMapping("/items")
    Result<PageResult<Map<String, Object>>> pageItems(@RequestParam("status") Integer status,
                                                      @RequestParam("keyword") String keyword,
                                                      @RequestParam("sellerId") Long sellerId,
                                                      @RequestParam("page") long page,
                                                      @RequestParam("pageSize") long pageSize);

    /** 管理端状态变更（审核通过=2/驳回=6/强制下架=8）；reason/rejectCode 由 item-service 写入审计字段 */
    @PostMapping("/items/{id}/status")
    Result<Object> updateItemStatus(@PathVariable("id") Long id,
                                    @RequestParam("status") Integer status,
                                    @RequestParam(value = "reason", required = false) String reason,
                                    @RequestParam(value = "rejectCode", required = false) String rejectCode);

    /** 商品统计：{onSale,pendingReview,offline} */
    @GetMapping("/items/stats")
    Result<Map<String, Object>> itemStats();

    /* ---------------- 上架审核队列（item_review 归 ershou_item） ---------------- */

    @GetMapping("/item-reviews")
    Result<PageResult<Map<String, Object>>> pageReviews(@RequestParam("status") Integer status,
                                                        @RequestParam("sellerId") Long sellerId,
                                                        @RequestParam("keyword") String keyword,
                                                        @RequestParam("page") long page,
                                                        @RequestParam("pageSize") long pageSize);

    @GetMapping("/item-reviews/{itemId}")
    Result<Map<String, Object>> reviewDetail(@PathVariable("itemId") Long itemId);

    @GetMapping("/item-reviews/stats")
    Result<Map<String, Object>> reviewStats();

    @PostMapping("/item-reviews/{itemId}/reviewed")
    Result<Map<String, Object>> markReviewed(@PathVariable("itemId") Long itemId,
                                             @RequestBody Map<String, Object> body);

    @PostMapping("/item-reviews/{itemId}/offline")
    Result<Map<String, Object>> offlineReview(@PathVariable("itemId") Long itemId,
                                              @RequestBody Map<String, Object> body);

    @PostMapping("/item-reviews/batch")
    Result<Map<String, Object>> batchReview(@RequestBody Map<String, Object> body);

    @PostMapping("/item-reviews/backfill")
    Result<Integer> backfillReviews(@RequestParam("limit") int limit);
}
