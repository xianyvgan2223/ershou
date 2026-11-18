package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** map-service 不可用时的降级 */
@Component
public class MapClientFallback implements MapClient {

    private static <T> Result<T> down() {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<List<Map<String, Object>>> listPoints(Long campusId, Integer status) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> getPoint(Long id) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> pointStats() {
        return down();
    }

    @Override
    public Result<Object> auditPoint(Long id, String action) {
        return down();
    }

    @Override
    public Result<Object> updatePointStatus(Long id, Integer status) {
        return down();
    }

    @Override
    public Result<Integer> cleanupEmptyPoints() {
        return down();
    }
}
