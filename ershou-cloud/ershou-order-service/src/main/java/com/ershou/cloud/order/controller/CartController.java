package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.order.dto.CartVO;
import com.ershou.cloud.order.dto.CheckoutDTO;
import com.ershou.cloud.order.service.CartService;
import com.ershou.cloud.order.service.MeetupOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 购物车接口（第 103 轮从单体 {@code CartController} 迁入 order-service，路径与 JSON 完全一致）。
 *
 * <p>全部需要登录（网关注入 X-User-Id，common 的 UserContext 读取）；未登录返回 10002。
 */
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final MeetupOrderService orderService;

    /** 购物车（按卖家分组） */
    @GetMapping
    public Result<List<CartVO>> list() {
        return Result.ok(cartService.listGrouped(UserContext.requireUserId()));
    }

    /** 加入购物车 body {itemId, quantity} */
    @PostMapping
    public Result<Void> add(@RequestBody Map<String, Object> body) {
        Long itemId = Long.valueOf(String.valueOf(body.get("itemId")));
        Integer quantity = body.get("quantity") == null
                ? 1 : Integer.valueOf(String.valueOf(body.get("quantity")));
        cartService.add(UserContext.requireUserId(), itemId, quantity);
        return Result.ok();
    }

    /** 修改数量 body {quantity} */
    @PatchMapping("/{cartId}")
    public Result<Void> update(@PathVariable Long cartId, @RequestBody Map<String, Object> body) {
        Integer quantity = body.get("quantity") == null
                ? 1 : Integer.valueOf(String.valueOf(body.get("quantity")));
        cartService.update(UserContext.requireUserId(), cartId, quantity);
        return Result.ok();
    }

    /** 移除 */
    @DeleteMapping("/{cartId}")
    public Result<Void> delete(@PathVariable Long cartId) {
        cartService.delete(UserContext.requireUserId(), cartId);
        return Result.ok();
    }

    /** 合并面交结算（按卖家拆单，共享 batchNo；返回 {batchNo, orders, skipped}） */
    @PostMapping("/checkout")
    public Result<Map<String, Object>> checkout(@Valid @RequestBody CheckoutDTO dto) {
        return Result.ok(orderService.checkout(UserContext.requireUserId(), dto));
    }
}
