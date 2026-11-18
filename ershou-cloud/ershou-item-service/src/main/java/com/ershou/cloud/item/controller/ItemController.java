package com.ershou.cloud.item.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.item.dto.ItemPublishDTO;
import com.ershou.cloud.item.dto.ItemQueryDTO;
import com.ershou.cloud.item.dto.ItemUpdateDTO;
import com.ershou.cloud.item.service.ItemService;
import com.ershou.cloud.item.vo.ItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商品用户接口（契约：{@code /api/v1/items/**} 路由到 item-service；路径与 JSON 与旧单体保持一致）。
 *
 * <p>鉴权：拆分后本服务不解析 token —— 网关 Sa-Token 统一校验并注入 {@code X-User-Id}，
 * 需要登录的接口用 {@link UserContext#requireUserId()}（未登录抛 10002，与单体会话语义一致）。
 */
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;

    @GetMapping
    public Result<PageResult<ItemVO>> page(ItemQueryDTO query) {
        return Result.ok(itemService.page(query, currentUserId()));
    }

    @GetMapping("/mine")
    public Result<PageResult<ItemVO>> mine(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(itemService.mine(pageNum, pageSize, UserContext.requireUserId()));
    }

    @GetMapping("/{id}")
    public Result<ItemVO> detail(@PathVariable Long id) {
        return Result.ok(itemService.detail(id, currentUserId()));
    }

    @PostMapping
    public Result<Map<String, Object>> publish(@Valid @RequestBody ItemPublishDTO dto) {
        return Result.ok(itemService.publish(dto, UserContext.requireUserId()));
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id,
                                              @Valid @RequestBody ItemUpdateDTO dto) {
        return Result.ok(itemService.update(id, dto, UserContext.requireUserId()));
    }

    @PostMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id) {
        itemService.offline(id, UserContext.requireUserId());
        return Result.ok();
    }

    @PostMapping("/{id}/relist")
    public Result<Map<String, Object>> relist(@PathVariable Long id) {
        return Result.ok(itemService.relist(id, UserContext.requireUserId()));
    }

    @PostMapping("/{id}/apply-relist")
    public Result<Map<String, Object>> applyRelist(@PathVariable Long id) {
        return Result.ok(itemService.applyRelist(id, UserContext.requireUserId()));
    }

    /** 公开读接口里「登录了就用登录态，没登录就是匿名」：拿不到就返回 null（favorited 按未收藏处理） */
    private Long currentUserId() {
        return UserContext.isLogin() ? UserContext.getUserId() : null;
    }
}
