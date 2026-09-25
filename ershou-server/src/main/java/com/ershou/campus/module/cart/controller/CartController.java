package com.ershou.campus.module.cart.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.cart.service.CartService;
import com.ershou.campus.module.cart.vo.CartVO;
import com.ershou.campus.module.order.dto.CheckoutDTO;
import com.ershou.campus.module.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "购物车")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final OrderService orderService;

    @Operation(summary = "购物车（按卖家分组）")
    @GetMapping
    public Result<List<CartVO>> list() {
        return Result.ok(cartService.listGrouped(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "加入购物车")
    @PostMapping
    public Result<Void> add(@RequestBody Map<String, Object> body) {
        Long itemId = Long.valueOf(String.valueOf(body.get("itemId")));
        Integer quantity = body.get("quantity") == null ? 1 : Integer.valueOf(String.valueOf(body.get("quantity")));
        cartService.add(StpUtil.getLoginIdAsLong(), itemId, quantity);
        return Result.ok();
    }

    @Operation(summary = "修改数量")
    @PatchMapping("/{cartId}")
    public Result<Void> update(@PathVariable Long cartId, @RequestBody Map<String, Object> body) {
        Integer quantity = body.get("quantity") == null ? 1 : Integer.valueOf(String.valueOf(body.get("quantity")));
        cartService.update(StpUtil.getLoginIdAsLong(), cartId, quantity);
        return Result.ok();
    }

    @Operation(summary = "移除")
    @DeleteMapping("/{cartId}")
    public Result<Void> delete(@PathVariable Long cartId) {
        cartService.delete(StpUtil.getLoginIdAsLong(), cartId);
        return Result.ok();
    }

    @Operation(summary = "合并面交结算（按卖家拆单，共享一个行程号）")
    @PostMapping("/checkout")
    public Result<Map<String, Object>> checkout(@Valid @RequestBody CheckoutDTO dto) {
        return Result.ok(orderService.checkout(StpUtil.getLoginIdAsLong(), dto));
    }
}
