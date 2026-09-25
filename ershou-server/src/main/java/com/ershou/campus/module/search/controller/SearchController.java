package com.ershou.campus.module.search.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.search.config.SearchProperties;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "搜索")
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final ItemIndexService itemIndexService;
    private final SearchProperties searchProperties;
    private final UserService userService;

    @Operation(summary = "搜索索引状态（公开）")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(itemIndexService.status());
    }

    @Operation(summary = "全量重建商品索引（管理员，后台执行）")
    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex() {
        StpUtil.checkLogin();
        if (!isAdmin(StpUtil.getLoginIdAsLong())) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }
        return Result.ok(itemIndexService.reindexAllAsync());
    }

    /** 管理员判定：白名单用户 或 role_flags 命中管理员位 */
    private boolean isAdmin(Long userId) {
        if (searchProperties.getAdminUserIds().contains(userId)) {
            return true;
        }
        SysUser user = userService.getById(userId);
        return user != null && user.getRoleFlags() != null
                && (user.getRoleFlags() & searchProperties.getAdminRoleFlag()) != 0;
    }
}
