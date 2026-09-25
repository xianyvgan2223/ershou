package com.ershou.campus.module.admin.controller;

import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.admin.dto.CreditAdjustDTO;
import com.ershou.campus.module.admin.dto.ReasonDTO;
import com.ershou.campus.module.admin.dto.UserStatusDTO;
import com.ershou.campus.module.admin.service.AdminAuthService;
import com.ershou.campus.module.admin.service.AdminItemService;
import com.ershou.campus.module.admin.service.AdminLogService;
import com.ershou.campus.module.admin.service.AdminOrderService;
import com.ershou.campus.module.admin.service.AdminUserService;
import com.ershou.campus.module.admin.vo.AdminItemVO;
import com.ershou.campus.module.admin.vo.AdminOrderVO;
import com.ershou.campus.module.admin.vo.AdminUserVO;
import com.ershou.campus.module.admin.vo.AuditLogVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 运营后台 · 管理与审计：商品管理、面交单查询、用户管理、操作日志。
 */
@Tag(name = "运营后台 · 管理与审计")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminManageController {

    private final AdminAuthService adminAuthService;
    private final AdminItemService itemService;
    private final AdminOrderService orderService;
    private final AdminUserService userService;
    private final AdminLogService logService;

    @Operation(summary = "商品管理列表（全部状态）")
    @GetMapping("/items")
    public Result<PageResult<AdminItemVO>> items(@RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Long sellerId,
                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(itemService.page(status, keyword, sellerId, pageNum, pageSize));
    }

    @Operation(summary = "强制下架商品（违规处置）")
    @PostMapping("/items/{id}/offline")
    public Result<Void> forceOffline(@PathVariable Long id, @Valid @RequestBody ReasonDTO dto) {
        itemService.forceOffline(id, dto.getReason(), adminAuthService.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "面交单查询")
    @GetMapping("/orders")
    public Result<PageResult<AdminOrderVO>> orders(@RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) Long buyerId,
                                                   @RequestParam(required = false) Long sellerId,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(orderService.page(status, buyerId, sellerId, pageNum, pageSize));
    }

    @Operation(summary = "用户列表（昵称 / 店铺名 / 手机号搜索）")
    @GetMapping("/users")
    public Result<PageResult<AdminUserVO>> users(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(userService.page(keyword, status, pageNum, pageSize));
    }

    @Operation(summary = "封禁 / 解封 / 限权用户")
    @PostMapping("/users/{id}/status")
    public Result<AdminUserVO> updateUserStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        return Result.ok(userService.updateStatus(id, dto, adminAuthService.requireAdminId()));
    }

    @Operation(summary = "调整用户信用分（留流水）")
    @PostMapping("/users/{id}/credit")
    public Result<AdminUserVO> adjustCredit(@PathVariable Long id, @Valid @RequestBody CreditAdjustDTO dto) {
        return Result.ok(userService.adjustCredit(id, dto, adminAuthService.requireAdminId()));
    }

    @Operation(summary = "运营操作审计日志")
    @GetMapping("/audit-logs")
    public Result<PageResult<AuditLogVO>> auditLogs(@RequestParam(required = false) String module,
                                                    @RequestParam(required = false) Long operatorId,
                                                    @RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(logService.page(module, operatorId, pageNum, pageSize));
    }
}
