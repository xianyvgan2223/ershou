package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** user-service 不可用时的降级：统一 80001 SERVICE_UNAVAILABLE */
@Component
public class UserClientFallback implements UserClient {

    private static <T> Result<T> down() {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<Map<String, Object>> getUser(Long id) {
        return down();
    }

    @Override
    public Result<List<Map<String, Object>>> batchUsers(String ids) {
        return down();
    }

    @Override
    public Result<PageResult<Map<String, Object>>> pageUsers(String keyword, Integer status, long page, long pageSize) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> userStats() {
        return down();
    }

    @Override
    public Result<Map<String, Object>> updateStatus(Long id, Map<String, Object> body) {
        return down();
    }

    @Override
    public Result<Integer> adjustCredit(Long id, Map<String, Object> body) {
        return down();
    }

    @Override
    public Result<List<Map<String, Object>>> schools() {
        return down();
    }

    @Override
    public Result<List<Map<String, Object>>> campuses(Long schoolId) {
        return down();
    }
}
