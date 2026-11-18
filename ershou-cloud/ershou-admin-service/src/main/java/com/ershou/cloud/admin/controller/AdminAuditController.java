package com.ershou.cloud.admin.controller;

import com.ershou.cloud.admin.dto.BatchItemAuditDTO;
import com.ershou.cloud.admin.dto.ItemRejectDTO;
import com.ershou.cloud.admin.dto.PointApproveDTO;
import com.ershou.cloud.admin.dto.PointRejectDTO;
import com.ershou.cloud.admin.dto.PointStatusDTO;
import com.ershou.cloud.admin.service.AdminAuthService;
import com.ershou.cloud.admin.service.AdminItemService;
import com.ershou.cloud.admin.service.AdminOverviewService;
import com.ershou.cloud.admin.service.AdminPointService;
import com.ershou.cloud.admin.service.AuditReasons;
import com.ershou.cloud.admin.vo.AdminItemVO;
import com.ershou.cloud.admin.vo.AdminPointVO;
import com.ershou.cloud.admin.vo.BatchAuditResultVO;
import com.ershou.cloud.admin.vo.RejectReasonVO;
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

import java.util.List;
import java.util.Map;

/**
 * 运营后台 · 审核工作台（路径/请求/响应与旧单体 AdminAuditController 完全一致）。
 *
 * <p>每个接口都先 {@link AdminAuthService#requireAdminId()}（登录 ≠ 管理员；身份来自网关 X-User-Id，
 * 管理员判定调 user-service inner）。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminAuthService adminAuthService;
    private final AdminOverviewService overviewService;
    private final AdminItemService itemService;
    private final AdminPointService pointService;

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        adminAuthService.requireAdminId();
        return Result.ok(overviewService.dashboard());
    }

    /** 商品审核队列（默认待审 status=1） */
    @GetMapping("/audit/items")
    public Result<PageResult<AdminItemVO>> auditItems(@RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(itemService.page(status == null ? 1 : status, keyword, null, pageNum, pageSize));
    }

    @PostMapping("/audit/items/{id}/approve")
    public Result<Void> approveItem(@PathVariable Long id) {
        itemService.approve(id, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @PostMapping("/audit/items/{id}/reject")
    public Result<Void> rejectItem(@PathVariable Long id, @Valid @RequestBody ItemRejectDTO dto) {
        itemService.reject(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @PostMapping("/audit/items/batch")
    public Result<BatchAuditResultVO> batchItems(@Valid @RequestBody BatchItemAuditDTO dto) {
        return Result.ok(itemService.batch(dto, adminAuthService.requireAdminId()));
    }

    /** 驳回原因模板（scope=item|point） */
    @GetMapping("/audit/reject-reasons")
    public Result<List<RejectReasonVO>> rejectReasons(@RequestParam(defaultValue = AuditReasons.SCOPE_ITEM) String scope) {
        adminAuthService.requireAdminId();
        return Result.ok(AuditReasons.of(scope).stream()
                .map(r -> new RejectReasonVO(r.code(), r.label(), r.scope()))
                .toList());
    }

    /** 点位列表（默认待审 auditStatus=0；负数=不限审核状态，status 筛地图启用状态） */
    @GetMapping("/audit/points")
    public Result<PageResult<AdminPointVO>> auditPoints(@RequestParam(required = false) Integer auditStatus,
                                                        @RequestParam(required = false) Long campusId,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        int effective = auditStatus == null ? 0 : auditStatus;
        // 负数 = 不限审核状态（与单体一致）；inner 接口用 null 表示不过滤
        Integer filter = effective < 0 ? null : effective;
        return Result.ok(pointService.page(filter, campusId, keyword, status, pageNum, pageSize));
    }

    @PostMapping("/audit/points/{id}/approve")
    public Result<Void> approvePoint(@PathVariable Long id, @Valid @RequestBody PointApproveDTO dto) {
        pointService.approve(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @PostMapping("/audit/points/{id}/reject")
    public Result<Void> rejectPoint(@PathVariable Long id, @Valid @RequestBody PointRejectDTO dto) {
        pointService.reject(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }

    @PostMapping("/points/{id}/status")
    public Result<Void> updatePointStatus(@PathVariable Long id, @Valid @RequestBody PointStatusDTO dto) {
        pointService.updateStatus(id, dto, adminAuthService.requireAdminId());
        return Result.ok();
    }
}
