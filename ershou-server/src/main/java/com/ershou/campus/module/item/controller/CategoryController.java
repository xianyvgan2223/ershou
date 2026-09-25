package com.ershou.campus.module.item.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.item.entity.ItemCategory;
import com.ershou.campus.module.item.mapper.ItemCategoryMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "分类")
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ItemCategoryMapper categoryMapper;

    @Operation(summary = "分类列表（公开）")
    @GetMapping
    public Result<List<ItemCategory>> list() {
        return Result.ok(categoryMapper.selectList(
                Wrappers.<ItemCategory>lambdaQuery().eq(ItemCategory::getStatus, 1).orderByAsc(ItemCategory::getSort)));
    }
}
