package com.ershou.campus.module.order.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.order.dto.CreateOrderDTO;
import com.ershou.campus.module.order.dto.OrderActionDTO;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.order.vo.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "面交单")
@RestController
@RequestMapping("/api/v1/meetup-orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "我的面交单（role=buyer|seller；可再按 itemId / status 过滤）")
    @GetMapping
    public Result<PageResult<OrderVO>> page(@RequestParam(defaultValue = "buyer") String role,
                                           @RequestParam(required = false) Long itemId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(orderService.page(StpUtil.getLoginIdAsLong(), role, itemId, status, pageNum, pageSize));
    }

    @Operation(summary = "我的面交待办数（顶栏红点：卖家待确认 + 买家待确认收货）")
    @GetMapping("/todo-count")
    public Result<Map<String, Object>> todoCount() {
        return Result.ok(orderService.todoCount(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "直接约面交（单件）")
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody CreateOrderDTO dto) {
        return Result.ok(orderService.createSingle(StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "面交单详情")
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.ok(orderService.detail(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "卖家接受")
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        orderService.accept(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "卖家拒绝")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestBody(required = false) OrderActionDTO dto) {
        orderService.reject(id, StpUtil.getLoginIdAsLong(), dto == null ? null : dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "取消面交")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id, @RequestBody(required = false) OrderActionDTO dto) {
        orderService.cancel(id, StpUtil.getLoginIdAsLong(), dto == null ? null : dto.getReason());
        return Result.ok();
    }

    @Operation(summary = "确认成交（卖家点一次即完成；买家确认只留痕）")
    @PostMapping("/{id}/confirm")
    public Result<Map<String, Object>> confirm(@PathVariable Long id) {
        return Result.ok(orderService.confirm(id, StpUtil.getLoginIdAsLong()));
    }
}
