package com.ershou.cloud.platform.feign;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.feign.model.InnerItemVO;
import com.ershou.cloud.platform.feign.model.PlatformItemSummaryVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 商品服务降级：item-service 不可用/接口未就绪时统一返回 code=90001。
 *
 * <p>必须是 Spring Bean，且 {@code @FeignClient(fallback=...)} 指向它，Sentinel 才能实例化。
 */
@Component
public class ItemClientFallback implements ItemClient {

    private static final int CODE_UNAVAILABLE = 90001;
    private static final String MSG = "商品服务暂不可用，请稍后重试";

    @Override
    public Result<InnerItemVO> get(Long id) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<List<InnerItemVO>> batch(String ids) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<PageResult<InnerItemVO>> platformPage(Long campusId, Long pointId, Long categoryId, Integer status,
                                                        String keyword, String sort, long page, long pageSize) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<InnerItemVO> platformDetail(Long id) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<Map<String, Object>> platformCreate(Map<String, Object> body) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<InnerItemVO> platformUpdate(Long id, Map<String, Object> body) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<Void> platformOffline(Long id, Map<String, String> body) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<Void> platformOnline(Long id, Integer validDays) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<PlatformItemSummaryVO> platformSummary(Long campusId) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }
}
