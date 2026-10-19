package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.stereotype.Component;

/**
 * {@link ItemClient} 的 Sentinel 降级实现（设计 4.5）。
 *
 * <p>item-service 不可用（宕机 / 熔断 / 超时）时返回 {@code code=90001「商品服务暂不可用」}，
 * 而不是把 500 抛给前端 —— 这样网关/前端看到的仍是统一的 {@code Result} 结构。
 *
 * <p>注意：必须注册成 Spring Bean（{@code @Component}）Sentinel 才能反射实例化 fallback；
 * 生效前提是 Nacos 里 {@code feign.sentinel.enabled=true}。
 */
@Component
public class ItemClientFallback implements ItemClient {

    /** 商品服务暂不可用（与单体 ResultCode.SYSTEM_ERROR 同码 90001，但文案更明确） */
    public static final int CODE_ITEM_SERVICE_UNAVAILABLE = 90001;

    @Override
    public Result<InnerItemVO> get(Long id) {
        return Result.fail(CODE_ITEM_SERVICE_UNAVAILABLE, "商品服务暂不可用");
    }

    @Override
    public Result<Integer> lock(Long id, Integer qty) {
        return Result.fail(CODE_ITEM_SERVICE_UNAVAILABLE, "商品服务暂不可用");
    }
}
