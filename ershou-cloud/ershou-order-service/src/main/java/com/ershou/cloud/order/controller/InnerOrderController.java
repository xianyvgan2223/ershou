package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.order.dto.OrderVO;
import com.ershou.cloud.order.service.MeetupOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部调试接口（设计 4.4：{@code GET /inner/orders/{id}}，仅用于演示/排查，不经过网关）。
 *
 * <p>不属于对外 API；生产应加内网访问白名单或服务鉴权（演示版从简）。
 */
@RestController
@RequestMapping("/inner/orders")
@RequiredArgsConstructor
public class InnerOrderController {

    private final MeetupOrderService orderService;

    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable("id") Long id) {
        return Result.ok(orderService.detail(id));
    }
}
