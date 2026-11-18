package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.order.dto.CreateOrderDTO;
import com.ershou.cloud.order.dto.CreditLogVO;
import com.ershou.cloud.order.dto.OrderActionDTO;
import com.ershou.cloud.order.dto.OrderVO;
import com.ershou.cloud.order.dto.UpdatePointDTO;
import com.ershou.cloud.order.service.CreditService;
import com.ershou.cloud.order.service.MeetupOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 面交单接口（第 103 轮从单体 {@code OrderController} 全量迁入 order-service）。
 *
 * <p>路径与响应 JSON 与旧单体完全一致（前端「我的面交」/私聊交易卡片/<b>约面交</b>零改动）：
 * <ul>
 *   <li>GET /api/v1/meetup-orders（role/itemId/status/pageNum/pageSize）</li>
 *   <li>GET /todo-count、GET /{id}、POST /（创建）、POST /{id}/accept|reject|cancel|confirm、PATCH /{id}/point</li>
 *   <li>GET /credit-logs（本人信用分流水，新增，与「信用分记录」需求对应）</li>
 * </ul>
 * 身份来自网关注入的 {@code X-User-Id}（UserContext）。
 */
@RestController
@RequestMapping("/api/v1/meetup-orders")
@RequiredArgsConstructor
public class MeetupOrderController {

    private final MeetupOrderService orderService;
    private final CreditService creditService;

    /** 我的面交单（role=buyer|seller，默认 buyer） */
    @GetMapping
    public Result<PageResult<OrderVO>> page(@RequestParam(defaultValue = "buyer") String role,
                                            @RequestParam(required = false) Long itemId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(orderService.page(UserContext.requireUserId(), role, itemId, status, pageNum, pageSize));
    }

    /** 待办数（顶栏红点） */
    @GetMapping("/todo-count")
    public Result<Map<String, Object>> todoCount() {
        return Result.ok(orderService.todoCount(UserContext.requireUserId()));
    }

    /** 我的信用分流水（分页） */
    @GetMapping("/credit-logs")
    public Result<PageResult<CreditLogVO>> creditLogs(@RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(creditService.page(UserContext.requireUserId(), pageNum, pageSize));
    }

    /** 直接约面交（单件） */
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody CreateOrderDTO dto) {
        return Result.ok(orderService.createSingle(UserContext.requireUserId(), dto));
    }

    /** 面交单详情 */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.ok(orderService.detail(id, UserContext.requireUserId()));
    }

    /** 卖家接受（锁商品） */
    @PostMapping("/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        orderService.accept(id, UserContext.requireUserId());
        return Result.ok();
    }

    /** 卖家拒绝 */
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                               @RequestBody(required = false) OrderActionDTO dto) {
        orderService.reject(id, UserContext.requireUserId(), dto == null ? null : dto.getReason());
        return Result.ok();
    }

    /** 取消（买卖双方均可，状态 1/2） */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id,
                               @RequestBody(required = false) OrderActionDTO dto) {
        orderService.cancel(id, UserContext.requireUserId(), dto == null ? null : dto.getReason());
        return Result.ok();
    }

    /** 改面交地点/时间（仅卖家，状态 1/2） */
    @PatchMapping("/{id}/point")
    public Result<OrderVO> updatePoint(@PathVariable Long id, @Valid @RequestBody UpdatePointDTO dto) {
        return Result.ok(orderService.updatePoint(id, UserContext.requireUserId(), dto));
    }

    /** 确认成交（卖家点一次即完成；买家确认只留痕；平台单买家确认即完成） */
    @PostMapping("/{id}/confirm")
    public Result<Map<String, Object>> confirm(@PathVariable Long id) {
        return Result.ok(orderService.confirm(id, UserContext.requireUserId()));
    }
}
