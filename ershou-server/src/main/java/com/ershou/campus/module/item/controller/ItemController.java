package com.ershou.campus.module.item.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.item.dto.ItemPublishDTO;
import com.ershou.campus.module.item.dto.ItemQueryDTO;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.item.vo.ItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "商品")
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @Operation(summary = "商品列表（公开）")
    @GetMapping
    public Result<PageResult<ItemVO>> page(ItemQueryDTO query) {
        return Result.ok(itemService.page(query));
    }

    @Operation(summary = "我的发布（需登录）")
    @GetMapping("/mine")
    public Result<PageResult<ItemVO>> mine(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        StpUtil.checkLogin();
        return Result.ok(itemService.mine(pageNum, pageSize, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "商品详情（公开）")
    @GetMapping("/{id}")
    public Result<ItemVO> detail(@PathVariable Long id) {
        return Result.ok(itemService.detail(id));
    }

    @Operation(summary = "发布商品（含 AI 机审）")
    @PostMapping
    public Result<Map<String, Object>> publish(@Valid @RequestBody ItemPublishDTO dto) {
        StpUtil.checkLogin();
        return Result.ok(itemService.publish(dto, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "下架商品")
    @PostMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id) {
        StpUtil.checkLogin();
        itemService.offline(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }
}