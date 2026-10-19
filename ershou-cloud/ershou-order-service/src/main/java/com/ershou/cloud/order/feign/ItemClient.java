package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 商品服务 OpenFeign 客户端（设计 4.3 内部接口，契约与 item-member 对齐）。
 *
 * <ul>
 *   <li>{@code GET /inner/items/{id}} → {@code Result<InnerItemVO>}；不存在返回 code=30001；</li>
 *   <li>{@code PUT /inner/items/{id}/lock?qty=} → {@code Result<Integer>}，data=1 成功 / 0 失败；
 *       item-service 侧是条件 UPDATE（{@code status=2 AND stock>=qty}）且方法带 {@code @Transactional}，
 *       因此这次调用会作为 Seata AT 分支事务注册，全局回滚时由 undo_log 把库存/状态还原。</li>
 * </ul>
 *
 * <p>服务不可用时走 {@link ItemClientFallback}（Sentinel 熔断降级，需 Nacos 配置
 * {@code feign.sentinel.enabled=true}），fallback 返回 code=90001「商品服务暂不可用」。
 */
@FeignClient(name = "ershou-item-service", contextId = "itemClient", path = "/inner/items",
        fallback = ItemClientFallback.class)
public interface ItemClient {

    @GetMapping("/{id}")
    Result<InnerItemVO> get(@PathVariable("id") Long id);

    @PutMapping("/{id}/lock")
    Result<Integer> lock(@PathVariable("id") Long id, @RequestParam("qty") Integer qty);
}
