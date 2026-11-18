package com.ershou.cloud.platform.feign;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.feign.model.InnerOrderVO;
import com.ershou.cloud.platform.feign.model.PlatformBuyResultVO;
import com.ershou.cloud.platform.feign.model.PlatformOrderCreateDTO;
import com.ershou.cloud.platform.feign.model.PlatformOrderSummaryVO;
import org.springframework.stereotype.Component;

/**
 * 订单服务降级：不可用/接口未就绪时统一返回 code=90001。
 *
 * <p>平台直购/抢购遇到降级会**补偿回滚** Redis 库存与已购 Hash（见 PlatformFlashSaleService），
 * 不会出现「用户没拿到单却占名额」。
 */
@Component
public class OrderClientFallback implements OrderClient {

    private static final int CODE_UNAVAILABLE = 90001;
    private static final String MSG = "订单服务暂不可用，请稍后重试";

    @Override
    public Result<PlatformBuyResultVO> createPlatformOrder(PlatformOrderCreateDTO dto) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<PageResult<InnerOrderVO>> platformOrders(Integer status, String keyword, long page, long pageSize) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<PlatformOrderSummaryVO> platformSummary(Long campusId) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }
}
