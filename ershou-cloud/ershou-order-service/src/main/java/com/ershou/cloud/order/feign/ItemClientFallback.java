package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link ItemClient} 的 Sentinel 降级实现。
 *
 * <p>item-service 不可用（迁移中/宕机/熔断）时返回 {@code code=90001「商品服务暂不可用」}，
 * 业务侧据此走降级路径（例如列表仍返回订单快照、锁库类操作记 warn 后继续/失败），
 * 不把 500 抛给前端。
 */
@Component
public class ItemClientFallback implements ItemClient {

    /** 商品服务暂不可用（与 common ResultCode.SYSTEM_ERROR 同码 90001） */
    public static final int CODE_ITEM_SERVICE_UNAVAILABLE = 90001;

    private Result<Integer> unavailableInt() {
        return Result.fail(CODE_ITEM_SERVICE_UNAVAILABLE, "商品服务暂不可用");
    }

    @Override
    public Result<InnerItemVO> get(Long id) {
        return Result.fail(CODE_ITEM_SERVICE_UNAVAILABLE, "商品服务暂不可用");
    }

    @Override
    public Result<List<InnerItemVO>> batch(String ids) {
        return Result.fail(CODE_ITEM_SERVICE_UNAVAILABLE, "商品服务暂不可用");
    }

    @Override
    public Result<Integer> lock(Long id, Integer qty) {
        return unavailableInt();
    }

    @Override
    public Result<Integer> meetupLock(Long id, Long buyerId, String expireAt) {
        return unavailableInt();
    }

    @Override
    public Result<Integer> meetupRelease(Long id) {
        return unavailableInt();
    }

    @Override
    public Result<Integer> meetupSold(Long id) {
        return unavailableInt();
    }

    @Override
    public Result<Integer> platformSettle(Long id) {
        return unavailableInt();
    }
}
