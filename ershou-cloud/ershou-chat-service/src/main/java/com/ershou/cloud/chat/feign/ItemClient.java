package com.ershou.cloud.chat.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 OpenFeign 客户端（full-split-contract 第三节 item-service 内部接口）。
 *
 * <ul>
 *   <li>{@code GET /inner/items/{id}} → 商品卡片（标题/封面/价格/状态/卖家 id）；</li>
 *   <li>{@code GET /inner/items/batch?ids=1,2} → 批量（未就绪时 ChatService 回退为逐个 get）。</li>
 * </ul>
 */
@FeignClient(name = "ershou-item-service", contextId = "chatItemClient", path = "/inner/items",
        fallback = ItemClientFallback.class)
public interface ItemClient {

    @GetMapping("/{id}")
    Result<ItemVO> get(@PathVariable("id") Long id);

    @GetMapping("/batch")
    Result<List<ItemVO>> batch(@RequestParam("ids") String ids);
}
