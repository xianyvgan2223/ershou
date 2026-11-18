package com.ershou.cloud.item.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.item.entity.ItemCategory;
import com.ershou.cloud.item.mapper.ItemCategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ItemCategoryMapper categoryMapper;

    @GetMapping
    public Result<List<ItemCategory>> list() {
        return Result.ok(categoryMapper.selectList(
                Wrappers.<ItemCategory>lambdaQuery().eq(ItemCategory::getStatus, 1).orderByAsc(ItemCategory::getSort)));
    }
}
