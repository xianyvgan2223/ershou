package com.ershou.campus.module.search.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.admin.service.AdminAuthService;
import com.ershou.campus.module.search.service.ItemIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@Tag(name = "搜索")
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final ItemIndexService itemIndexService;
    private final AdminAuthService adminAuthService;

    @Operation(summary = "搜索索引状态（公开）")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(itemIndexService.status());
    }

    @Operation(summary = "全量重建商品索引（管理员，后台执行）")
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex() {
        Long adminId = adminAuthService.requireAdminId();
        log.info("[search][es] {} 触发全量重建", adminId);
        return Result.ok(itemIndexService.reindexAllAsync());
    }
}
