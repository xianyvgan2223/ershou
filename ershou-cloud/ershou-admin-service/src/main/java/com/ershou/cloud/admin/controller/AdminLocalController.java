package com.ershou.cloud.admin.controller;

import com.ershou.cloud.admin.dto.ReportHandleDTO;
import com.ershou.cloud.admin.service.AdminAiAuditService;
import com.ershou.cloud.admin.service.AdminAuthService;
import com.ershou.cloud.admin.service.AdminReportService;
import com.ershou.cloud.admin.vo.AiAuditRecordVO;
import com.ershou.cloud.admin.vo.ReportTicketVO;
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
 * 运营后台 · 本地域新增接口（T107，Lead 已批准命名与权限）。
 *
 * <p>只读写本服务自己的库 ershou_admin：
 * <ul>
 *   <li>{@code GET /api/v1/admin/ai-audit-records}：AI 审核记录（只读；旧单体从未落库，当前为空）；</li>
 *   <li>{@code GET /api/v1/admin/reports}：举报工单列表；</li>
 *   <li>{@code POST /api/v1/admin/reports/{id}/handle}：举报工单处置（写审计日志）。</li>
 * </ul>
 * 全部要求管理员身份。
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminLocalController {

    private final AdminAuthService adminAuthService;
    private final AdminAiAuditService aiAuditService;
    private final AdminReportService reportService;

    @GetMapping("/ai-audit-records")
    public Result<PageResult<AiAuditRecordVO>> aiAuditRecords(@RequestParam(required = false) String bizType,
                                                              @RequestParam(required = false) Long bizId,
                                                              @RequestParam(required = false) Integer decision,
                                                              @RequestParam(defaultValue = "1") long pageNum,
                                                              @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(aiAuditService.page(bizType, bizId, decision, pageNum, pageSize));
    }

    @GetMapping("/reports")
    public Result<PageResult<ReportTicketVO>> reports(@RequestParam(required = false) Integer status,
                                                      @RequestParam(required = false) Integer reportType,
                                                      @RequestParam(required = false) Long reporterId,
                                                      @RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "20") long pageSize) {
        adminAuthService.requireAdminId();
        return Result.ok(reportService.page(status, reportType, reporterId, pageNum, pageSize));
    }

    @PostMapping("/reports/{id}/handle")
    public Result<ReportTicketVO> handleReport(@PathVariable Long id, @Valid @RequestBody ReportHandleDTO dto) {
        return Result.ok(reportService.handle(id, dto, adminAuthService.requireAdminId()));
    }
}
