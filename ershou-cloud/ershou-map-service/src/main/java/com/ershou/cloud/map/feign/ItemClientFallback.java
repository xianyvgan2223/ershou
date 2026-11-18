package com.ershou.cloud.map.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ItemClient} 的 Sentinel 降级实现（契约：Feign 需配 fallback 返回统一错误码）。
 *
 * <p>item-service 不可用（宕机 / 熔断 / 超时）时返回 {@code code=80001}，本服务业务层捕获后
 * 降级为"无商品统计"（地图点位仍可返回、清理任务会跳过），不会把 500 抛给前端。
 * 生效前提：Nacos 里 {@code feign.sentinel.enabled=true}（同 order-service）。
 */
@Component
public class ItemClientFallback implements ItemClient {

    @Override
    public Result<InnerItemVO> get(Long id) {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "商品服务暂不可用");
    }

    @Override
    public Result<List<PointItemStatVO>> countByPoint(Long campusId) {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "商品服务暂不可用");
    }

    @Override
    public Result<Object> byPoint(Long campusId, Long pointId, long page, long pageSize) {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "商品服务暂不可用");
    }
}
