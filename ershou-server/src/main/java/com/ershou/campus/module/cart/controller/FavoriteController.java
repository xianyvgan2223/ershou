package com.ershou.campus.module.cart.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.cart.service.FavoriteService;
import com.ershou.campus.module.item.vo.ItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "收藏")
@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "我的收藏")
    @GetMapping
    public Result<PageResult<ItemVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(favoriteService.page(StpUtil.getLoginIdAsLong(), pageNum, pageSize));
    }

    @Operation(summary = "收藏")
    @PostMapping("/{itemId}")
    public Result<Void> add(@PathVariable Long itemId) {
        favoriteService.add(StpUtil.getLoginIdAsLong(), itemId);
        return Result.ok();
    }

    @Operation(summary = "取消收藏")
    @DeleteMapping("/{itemId}")
    public Result<Void> remove(@PathVariable Long itemId) {
        favoriteService.remove(StpUtil.getLoginIdAsLong(), itemId);
        return Result.ok();
    }
}
