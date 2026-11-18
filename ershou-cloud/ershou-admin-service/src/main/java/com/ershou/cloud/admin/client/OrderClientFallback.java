package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.Map;

/** order-service 不可用时的降级 */
@Component
public class OrderClientFallback implements OrderClient {

    private static <T> Result<T> down() {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<PageResult<Map<String, Object>>> pageOrders(Long userId, Integer status, String keyword,
                                                              long page, long pageSize) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> getOrder(Long id) {
        return down();
    }

    @Override
    public Result<Map<String, Object>> orderStats() {
        return down();
    }
}
