package com.ershou.cloud.admin.controller;

import com.ershou.cloud.admin.dto.CreditAdjustDTO;
import com.ershou.cloud.admin.dto.ReasonDTO;
import com.ershou.cloud.admin.dto.UserStatusDTO;
import com.ershou.cloud.admin.service.AdminAuthService;
import com.ershou.cloud.admin.service.AdminItemService;
import com.ershou.cloud.admin.service.AdminLogService;
import com.ershou.cloud.admin.service.AdminOrderService;
import com.ershou.cloud.admin.service.AdminPointService;
import com.ershou.cloud.admin.service.AdminUserService;
import com.ershou.cloud.admin.vo.AdminItemVO;
import com.ershou.cloud.admin.vo.AdminOrderVO;
import com.ershou.cloud.admin.vo.AdminUserVO;
import com.ershou.cloud.admin.vo.AuditLogVO;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营后台 · 管理与审计（路径/请求/响应与旧单体 AdminManageController 完全一致）。
 *
 * <p>商品/用户/点位数据来自 item/user/map 服务 inner；操作日志读本服务 ershou_admin.sys_audit_log。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminManageController {

    private final AdminAuthService adminAuthService;
    private final AdminItemService itemService;
    private final AdminOrderService orderService;
    private final AdminUserService userService;
    private final AdminLogService logService;
    private final AdminPointService pointService;

    /** 清理没有商品的面交点（立即执行；定时任务每天 03:10 仍由 map-service 跑） */
    @PostMapping("/trade-points/cleanup-empty")
    public Result<Integer> cleanupEmptyPoints() {
        return Result.ok(pointService.cleanupEmptyPoints(adminAuthService.requireAdminId()));
    }

    /** 商品管理列表（全部状态） */
    @GetMapping("/items")
    public Result<PageResult<AdminItemVO>> items(@RequestParam(required = false) Integer status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Long sellerId,
                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(itemService.page(status, keyword, sellerId, pageNum, pageSize));
    }

    /** 强制下架商品（违规处置，原因必填） */
    @PostMapping("/items/{id}/offline")
    public Result<Void> forceOffline(@PathVariable Long id, @Valid @RequestBody ReasonDTO dto) {
        itemService.forceOffline(id, dto.getReason(), adminAuthService.requireAdminId());
        return Result.ok();
    }

    /** 面交单查询（订单号 / 商品标题搜索） */
    @GetMapping("/orders")
    public Result<PageResult<AdminOrderVO>> orders(@RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) Long buyerId,
                                                   @RequestParam(required = false) Long sellerId,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(orderService.page(status, buyerId, sellerId, keyword, pageNum, pageSize));
    }

    /** 用户列表（昵称 / 店铺名 / 手机号搜索） */
    @GetMapping("/users")
    public Result<PageResult<AdminUserVO>> users(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                 @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(userService.page(keyword, status, pageNum, pageSize));
    }

    /** 封禁 / 解封 / 限权用户 */
    @PostMapping("/users/{id}/status")
    public Result<AdminUserVO> updateUserStatus(@PathVariable Long id, @Valid @RequestBody UserStatusDTO dto) {
        return Result.ok(userService.updateStatus(id, dto, adminAuthService.requireAdminId()));
    }

    /** 调整用户信用分（user-service 留 credit_log 流水） */
    @PostMapping("/users/{id}/credit")
    public Result<AdminUserVO> adjustCredit(@PathVariable Long id, @Valid @RequestBody CreditAdjustDTO dto) {
        return Result.ok(userService.adjustCredit(id, dto, adminAuthService.requireAdminId()));
    }

    /** 运营操作审计日志（可按操作类型与时间区间筛选，读本服务库） */
    @GetMapping("/audit-logs")
    public Result<PageResult<AuditLogVO>> auditLogs(@RequestParam(required = false) String module,
                                                    @RequestParam(required = false) Long operatorId,
                                                    @RequestParam(required = false) String action,
                                                    @RequestParam(required = false) String startTime,
                                                    @RequestParam(required = false) String endTime,
                                                    @RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(logService.page(module, operatorId, action, startTime, endTime, pageNum, pageSize));
    }
}
