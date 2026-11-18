package com.ershou.cloud.item.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * order-service 内部接口（T102 新增，Lead 已协调 order-member）。
 *
 * <p>商品下架/过期/违规处置时，旧单体会把挂在该商品上的约面交单一起作废；
 * 拆分后 meetup_order 在 ershou_order 库，item-service 不能跨库写 → 通过该 inner 接口通知。
 * order-service 未实现/不可用时降级为只改商品状态（记录 warn，不阻塞业务）。
 */
@FeignClient(name = "ershou-order-service", contextId = "itemOrderClient", path = "/inner/orders")
public interface OrderClient {

    @PostMapping("/close-by-item")
    Result<Integer> closeByItem(@RequestParam("itemId") Long itemId, @RequestParam("reason") String reason);
}
