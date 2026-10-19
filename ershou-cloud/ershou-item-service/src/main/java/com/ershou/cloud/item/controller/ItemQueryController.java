package com.ershou.cloud.item.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.item.entity.ItemCategory;
import com.ershou.cloud.item.mapper.ItemCategoryMapper;
import com.ershou.cloud.item.service.ItemQueryService;
import com.ershou.cloud.item.vo.ItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品公开读接口（设计 4.3）。网关把 {@code GET /api/v1/items/**} 路由到本服务。
 *
 * <p>路径与单体保持一致，Web 首页/详情页无需改代码（只改代理端口）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ItemQueryController {

    /** 与单体 ResultCode.ITEM_NOT_FOUND 同码（order-service/Web 已按 30001 处理） */
    private static final int CODE_ITEM_NOT_FOUND = 30001;

    private final ItemQueryService itemQueryService;
    private final ItemCategoryMapper categoryMapper;

    /** 商品列表：只返回在售(status=2)；keyword 为标题/描述模糊匹配；sort=latest|price_asc|price_desc */
    @GetMapping("/items")
    public Result<PageResult<ItemVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Long categoryId,
                                           @RequestParam(defaultValue = "latest") String sort) {
        return Result.ok(itemQueryService.page(pageNum, pageSize, keyword, categoryId, sort));
    }

    /** 商品详情：Redis db2 缓存 + 浏览量。不存在 → code=30001（与单体一致） */
    @GetMapping("/items/{id}")
    public Result<ItemVO> detail(@PathVariable Long id) {
        ItemVO vo = itemQueryService.detail(id);
        if (vo == null) {
            return Result.fail(CODE_ITEM_NOT_FOUND, "商品不存在或已删除");
        }
        return Result.ok(vo);
    }

    /**
     * 分类列表。
     *
     * <p>分类数据在 {@code ershou_item.item_category}（本服务 DB），而设计 4.1 当前把
     * {@code GET /api/v1/categories} 路由到了 user-service —— 那里没有分类表，建议网关改为路由到本服务
     * （已向 platform-dev/lead 反馈）。这里先提供与单体同结构的分类列表接口备用。
     */
    @GetMapping("/categories")
    public Result<List<ItemCategory>> categories() {
        return Result.ok(categoryMapper.selectList(Wrappers.<ItemCategory>lambdaQuery()
                .eq(ItemCategory::getStatus, 1)
                .orderByAsc(ItemCategory::getSort)
                .orderByAsc(ItemCategory::getId)));
    }
}
