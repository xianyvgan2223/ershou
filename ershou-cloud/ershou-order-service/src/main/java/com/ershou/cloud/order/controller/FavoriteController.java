package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.order.dto.ItemBriefVO;
import com.ershou.cloud.order.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏接口（第 103 轮从单体 {@code FavoriteController} 迁入 order-service，路径/JSON 一致）。
 *
 * <p>「我的收藏」返回 PageResult&lt;ItemBriefVO&gt;（旧前端 ItemVO 兼容子集，ItemCard 可直接渲染）。
 * 已收藏夹分组（favorite_folder 表）已迁入本库，但旧单体无分组接口，本轮无对应端点（遗留）。
 */
@RestController
@RequestMapping("/api/v1/favorites")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /** 我的收藏分页 */
    @GetMapping
    public Result<PageResult<ItemBriefVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(favoriteService.page(UserContext.requireUserId(), pageNum, pageSize));
    }

    /** 收藏 */
    @PostMapping("/{itemId}")
    public Result<Void> add(@PathVariable Long itemId) {
        favoriteService.add(UserContext.requireUserId(), itemId);
        return Result.ok();
    }

    /** 取消收藏 */
    @DeleteMapping("/{itemId}")
    public Result<Void> remove(@PathVariable Long itemId) {
        favoriteService.remove(UserContext.requireUserId(), itemId);
        return Result.ok();
    }
}
