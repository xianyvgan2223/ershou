package com.ershou.campus.module.item.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.item.dto.ItemPublishDTO;
import com.ershou.campus.module.item.dto.ItemUpdateDTO;
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

    @Operation(summary = "商品列表（公开；登录后附带本人收藏态）")
    @GetMapping
    public Result<PageResult<ItemVO>> page(ItemQueryDTO query) {
        return Result.ok(itemService.page(query, currentUserId()));
    }

    @Operation(summary = "我的发布（需登录）")
    @GetMapping("/mine")
    public Result<PageResult<ItemVO>> mine(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        StpUtil.checkLogin();
        return Result.ok(itemService.mine(pageNum, pageSize, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "商品详情（公开；登录后附带本人收藏态）")
    @GetMapping("/{id}")
    public Result<ItemVO> detail(@PathVariable Long id) {
        return Result.ok(itemService.detail(id, currentUserId()));
    }

    @Operation(summary = "发布商品（含 AI 机审）")
    @PostMapping
    public Result<Map<String, Object>> publish(@Valid @RequestBody ItemPublishDTO dto) {
        StpUtil.checkLogin();
        return Result.ok(itemService.publish(dto, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "编辑商品（标题 / 价格 / 描述 / 图片等，只提交改动的字段）")
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @Valid @RequestBody ItemUpdateDTO dto) {
        StpUtil.checkLogin();
        return Result.ok(itemService.update(id, dto, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "下架商品")
    @PostMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id) {
        StpUtil.checkLogin();
        itemService.offline(id, StpUtil.getLoginIdAsLong());
        return Result.ok();
    }

    @Operation(summary = "重新上架（已下架 / 已过期，重新挂满有效期）")
    @PostMapping("/{id}/relist")
    public Result<Map<String, Object>> relist(@PathVariable Long id) {
        StpUtil.checkLogin();
        return Result.ok(itemService.relist(id, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "违规商品二次申请上架（提交后进入审核，审核通过才回到在售）")
    @PostMapping("/{id}/apply-relist")
    public Result<Map<String, Object>> applyRelist(@PathVariable Long id) {
        StpUtil.checkLogin();
        return Result.ok(itemService.applyRelist(id, StpUtil.getLoginIdAsLong()));
    }

    /** 公开读接口里「登录了就用登录态，没登录就是匿名」：拿不到就返回 null，由服务端按未收藏处理 */
    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}