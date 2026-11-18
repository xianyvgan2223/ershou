package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** item-service 不可用时的降级：统一 80001 SERVICE_UNAVAILABLE */
@Component
public class ItemClientFallback implements ItemClient {

    private static <T> Result<T> down() {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<Map<String, Object>> getItem(Long id) {
        return down();
    }

    @Override
    public Result<List<Map<String, Object>>> batchItems(String ids) {
        return down();
    }

    @Override
    public Result<PageResult<Map<String, Object>>> pageItems(Integer status, String keyword, Long sellerId,
                                                             long page, long pageSize) {
        return down();
    }

    @Override
    public Result<Object> updateItemStatus(Long id, Integer status, String reason, String rejectCode) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> itemStats() {
        return down();
    }

    @Override
    public Result<PageResult<Map<String, Object>>> pageReviews(Integer status, Long sellerId, String keyword,
                                                               long page, long pageSize) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> reviewDetail(Long itemId) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> reviewStats() {
        return down();
    }

    @Override
    public Result<Map<String, Object>> markReviewed(Long itemId, Map<String, Object> body) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> offlineReview(Long itemId, Map<String, Object> body) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> batchReview(Map<String, Object> body) {
        return down();
    }

    @Override
    public Result<Integer> backfillReviews(int limit) {
        return down();
    }
}
