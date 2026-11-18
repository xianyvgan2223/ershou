package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * order-service 内部接口：面交单查询 + 统计。
 *
 * <p>与 order-member（T103）实际实现对齐（2026-11-13）：
 * {@code GET /inner/orders?userId=&status=&keyword=&page=&pageSize=}
 * —— userId 语义是"该用户作为买家或卖家"；管理端的 buyerId/sellerId 双条件无法同时下推，
 * 传了一个就按该用户参与过滤，两个都传时按 buyerId 过滤（遗留，见交付报告）。
 */
@FeignClient(name = "ershou-order-service", contextId = "adminOrderClient", path = "/inner",
        fallback = OrderClientFallback.class)
public interface OrderClient {

    @GetMapping("/orders")
    Result<PageResult<Map<String, Object>>> pageOrders(@RequestParam("userId") Long userId,
                                                       @RequestParam("status") Integer status,
                                                       @RequestParam("keyword") String keyword,
                                                       @RequestParam("page") long page,
                                                       @RequestParam("pageSize") long pageSize);

    @GetMapping("/orders/{id}")
    Result<Map<String, Object>> getOrder(@PathVariable("id") Long id);

    /** 订单统计：{total,today,completed} */
    @GetMapping("/orders/stats")
    Result<Map<String, Object>> orderStats();
}
