package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.order.dto.CreateOrderDTO;
import com.ershou.cloud.order.dto.OrderVO;
import com.ershou.cloud.order.service.MeetupOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面交单对外接口（经 Gateway 8088 路由，需登录）。
 *
 * <p>登录用户由网关鉴权后注入 {@code X-User-Id} 头；common 的
 * {@code UserContextInterceptor}（自动装配）把它放进 {@link UserContext}，
 * 业务代码统一用 {@code UserContext.requireUserId()} 取 —— 不直接摸 header。
 * 未带该头（未登录/绕过网关）时返回 10002。
 */
@RestController
@RequestMapping("/api/v1/meetup-orders")
@RequiredArgsConstructor
public class MeetupOrderController {

    private final MeetupOrderService orderService;

    /**
     * 创建面交单（POST /api/v1/meetup-orders）。
     * body：{@code {itemId, quantity, remark, debugFail}}；debugFail=true 用于 Seata 全局回滚演示。
     */
    @PostMapping
    public Result<OrderVO> create(@Valid @RequestBody CreateOrderDTO dto) {
        return Result.ok(orderService.create(UserContext.requireUserId(), dto));
    }

    /** 我的面交单分页（GET /api/v1/meetup-orders/mine?status=） */
    @GetMapping("/mine")
    public Result<PageResult<OrderVO>> mine(@RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(orderService.mine(UserContext.requireUserId(), status, pageNum, pageSize));
    }
}
