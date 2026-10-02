package com.ershou.campus.module.admin.controller;

import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.admin.dto.BatchItemAuditDTO;
import com.ershou.campus.module.admin.dto.ItemRejectDTO;
import com.ershou.campus.module.admin.dto.PointApproveDTO;
import com.ershou.campus.module.admin.dto.PointRejectDTO;
import com.ershou.campus.module.admin.dto.PointStatusDTO;
import com.ershou.campus.module.admin.service.AdminAuthService;
import com.ershou.campus.module.admin.service.AdminItemService;
import com.ershou.campus.module.admin.service.AdminOverviewService;
import com.ershou.campus.module.admin.service.AdminPointService;
import com.ershou.campus.module.admin.service.AuditReasons;
import com.ershou.campus.module.admin.vo.AdminItemVO;
import com.ershou.campus.module.admin.vo.AdminPointVO;
import com.ershou.campus.module.admin.vo.BatchAuditResultVO;
import com.ershou.campus.module.admin.vo.RejectReasonVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 运营后台 · 审核工作台。
 * 所有接口在方法内校验运营身份（Sa-Token 路由已要求登录）。
 */
@Tag(name = "运营后台 · 审核工作台")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminAuthService adminAuthService;
    private final AdminOverviewService overviewService;
    private final AdminItemService itemService;
    private final AdminPointService pointService;

    @Operation(summary = "工作台概览（待办统计）")
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        adminAuthService.requireAdminId();
        return Result.ok(overviewService.dashboard());
    }

    @Operation(summary = "商品审核队列（默认待审 status=1）")
    @GetMapping("/audit/items")
    public Result<PageResult<AdminItemVO>> auditItems(@RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(itemService.page(status == null ? 1 : status, keyword, null, pageNum, pageSize));
    }

    @Operation(summary = "商品审核通过")
    @PostMapping("/audit/items/{id}/approve")
    public Result<Void> approveItem(@PathVariable Long id) {
        itemService.approve(id, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "商品审核驳回")
    @PostMapping("/audit/items/{id}/reject")
    public Result<Void> rejectItem(@PathVariable Long id, @Valid @RequestBody ItemRejectDTO dto) {
        itemService.reject(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "商品批量通过 / 驳回")
    @PostMapping("/audit/items/batch")
    public Result<BatchAuditResultVO> batchItems(@Valid @RequestBody BatchItemAuditDTO dto) {
        return Result.ok(itemService.batch(dto, adminAuthService.requireAdminId()));
    }

    @Operation(summary = "驳回原因模板（scope=item|point）")
    @GetMapping("/audit/reject-reasons")
    public Result<List<RejectReasonVO>> rejectReasons(@RequestParam(defaultValue = AuditReasons.SCOPE_ITEM) String scope) {
        adminAuthService.requireAdminId();
        return Result.ok(AuditReasons.of(scope).stream()
                .map(r -> new RejectReasonVO(r.code(), r.label(), r.scope()))
                .toList());
    }

    @Operation(summary = "点位列表（默认待审 auditStatus=0；auditStatus 传负数=不限审核状态，status 筛地图启用状态）")
    @GetMapping("/audit/points")
    public Result<PageResult<AdminPointVO>> auditPoints(@RequestParam(required = false) Integer auditStatus,
                                                        @RequestParam(required = false) Long campusId,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(pointService.page(auditStatus == null ? 0 : auditStatus, campusId, keyword, status,
                pageNum, pageSize));
    }

    @Operation(summary = "点位审核通过（可顺带校准安全分与指引）")
    @PostMapping("/audit/points/{id}/approve")
    public Result<Void> approvePoint(@PathVariable Long id, @Valid @RequestBody PointApproveDTO dto) {
        pointService.approve(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "点位审核驳回")
    @PostMapping("/audit/points/{id}/reject")
    public Result<Void> rejectPoint(@PathVariable Long id, @Valid @RequestBody PointRejectDTO dto) {
        pointService.reject(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @Operation(summary = "启用 / 停用（下架）面交点")
    @PostMapping("/points/{id}/status")
    public Result<Void> updatePointStatus(@PathVariable Long id, @Valid @RequestBody PointStatusDTO dto) {
        pointService.updateStatus(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }
}
