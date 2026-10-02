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
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(summary = "全量重建商品索引（管理员；默认为后台执行，加 sync=true 则同步等待完成）")
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex(
            @RequestParam(defaultValue = "false") boolean sync) {
        Long adminId = adminAuthService.requireAdminId();
        log.info("[search][es] {} 触发全量重建（sync={}）", adminId, sync);
        // 默认异步：数据量大时不阻塞请求。sync=true 给"跑完要立刻断言索引一致"的测试/脚本用 ——
        // 异步时接口返回"已开始"，索引还在删+重建中途，这时候去查会读到中间状态（实测读到过 0）。
        if (sync) {
            return Result.ok(itemIndexService.reindexAll());
        }
        return Result.ok(itemIndexService.reindexAllAsync());
    }
}
