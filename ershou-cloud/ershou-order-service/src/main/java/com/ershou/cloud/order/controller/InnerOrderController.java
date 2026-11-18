package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.order.dto.InnerCreateOrderDTO;
import com.ershou.cloud.order.dto.OrderVO;
import com.ershou.cloud.order.service.MeetupOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部接口（不经过网关，供 admin-service / platform-service / item-service 调用）。
 *
 * <p>契约（full-split-contract 第三节 + Lead 追加）：
 * <ul>
 *   <li>GET /inner/orders/{id} → 面交单详情（含 buyer/seller/item/status）</li>
 *   <li>GET /inner/orders?userId=&status=&keyword=&page=&pageSize= → 管理端分页</li>
 *   <li>GET /inner/orders/stats → {total,today,completed}（admin 概览）</li>
 *   <li>POST /inner/orders/{id}/status?status=&reason= → 管理端处置</li>
 *   <li>POST /inner/orders/close-by-item?itemId=&reason= → 商品下架/过期时作废挂单（返回影响行数）</li>
 *   <li>POST /inner/orders（body InnerCreateOrderDTO）→ 内部建单（个人意向单 / 平台款）</li>
 *   <li>POST /inner/orders/platform（body {itemId,buyerId,quantity,dealPrice,pointId}）→ 平台代卖下单</li>
 *   <li>GET /inner/orders/platform?status=&keyword=&page=&pageSize= → 平台订单分页</li>
 *   <li>GET /inner/orders/platform/summary?campusId= → {total,gmv,pendingPickup,completed}</li>
 * </ul>
 */
@RestController
@RequestMapping("/inner/orders")
@RequiredArgsConstructor
public class InnerOrderController {

    private final MeetupOrderService orderService;

    /** 管理端：面交单分页 */
    @GetMapping
    public Result<PageResult<OrderVO>> page(@RequestParam(required = false) Long userId,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(orderService.innerPage(userId, status, keyword, page, pageSize));
    }

    /** 管理端：订单统计 {total,today,completed} */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(orderService.stats());
    }

    /** 平台服务：平台订单分页（仅 seller_id=平台账号） */
    @GetMapping("/platform")
    public Result<PageResult<OrderVO>> platformPage(@RequestParam(required = false) Integer status,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(orderService.platformPage(status, keyword, page, pageSize));
    }

    /** 平台服务：平台订单概览 */
    @GetMapping("/platform/summary")
    public Result<Map<String, Object>> platformSummary(@RequestParam(required = false) Long campusId) {
        return Result.ok(orderService.platformSummary(campusId));
    }

    /** 平台服务：平台代卖下单（item-service 条件扣库存 + 平台单落库） */
    @PostMapping("/platform")
    public Result<Map<String, Object>> createPlatform(@RequestBody InnerCreateOrderDTO dto) {
        return Result.ok(orderService.createPlatformOrder(dto));
    }

    /** 内部建单：platformOrder=true/dealPrice 非空=平台款；否则生成个人意向单 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody InnerCreateOrderDTO dto) {
        return Result.ok(orderService.innerCreate(dto));
    }

    /** 商品下架/过期等：作废该商品上所有进行中面交单，返回影响行数 */
    @PostMapping("/close-by-item")
    public Result<Integer> closeByItem(@RequestParam Long itemId,
                                       @RequestParam(required = false) String reason) {
        return Result.ok(orderService.closeActiveOrders(itemId,
                reason == null || reason.isBlank() ? "商品已不可交易，本次约面交自动作废" : reason));
    }

    /** 管理端处置：强制改状态（3=完成会置商品售出；4/5=释放商品锁） */
    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @RequestParam Integer status,
                                     @RequestParam(required = false) String reason) {
        orderService.innerUpdateStatus(id, status, reason);
        return Result.ok();
    }

    /** 面交单详情（管理端/平台/调试用） */
    @GetMapping("/{id}")
    public Result<OrderVO> detail(@PathVariable Long id) {
        return Result.ok(orderService.innerDetail(id));
    }
}
