package com.ershou.cloud.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.admin.client.UserClient;
import com.ershou.cloud.admin.dto.ReportHandleDTO;
import com.ershou.cloud.admin.entity.ReportTicket;
import com.ershou.cloud.admin.mapper.ReportTicketMapper;
import com.ershou.cloud.admin.vo.ReportTicketVO;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 举报/仲裁工单管理（T107 新增；旧单体只有表没有接口）。
 *
 * <p>写自己的库；处置动作写审计日志。举报人/处置人昵称通过 user-service 批量补充，失败容忍为空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_PENDING = 1;
    private static final int STATUS_PROCESSING = 2;
    private static final int STATUS_JUDGED = 3;
    private static final int STATUS_APPEAL = 4;
    private static final int STATUS_CLOSED = 5;

    private final ReportTicketMapper reportMapper;
    private final UserClient userClient;
    private final AuditLogService auditLogService;

    public PageResult<ReportTicketVO> page(Integer status, Integer reportType, Long reporterId,
                                           long pageNum, long pageSize) {
        LambdaQueryWrapper<ReportTicket> w = Wrappers.<ReportTicket>lambdaQuery();
        if (status != null) {
            w.eq(ReportTicket::getStatus, status);
        }
        if (reportType != null) {
            w.eq(ReportTicket::getReportType, reportType);
        }
        if (reporterId != null) {
            w.eq(ReportTicket::getReporterId, reporterId);
        }
        w.orderByDesc(ReportTicket::getId);
        Page<ReportTicket> page = reportMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        Map<Long, String> nicknames = resolveNicknames(page.getRecords());
        return PageResult.of(page, ticket -> toVO(ticket, nicknames));
    }

    @Transactional(rollbackFor = Exception.class)
    public ReportTicketVO handle(Long id, ReportHandleDTO dto, Long operatorId) {
        ReportTicket before = reportMapper.selectById(id);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "举报工单不存在");
        }
        if (Objects.equals(before.getStatus(), STATUS_CLOSED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "该工单已结案，不能重复处置");
        }
        int targetStatus = dto.getStatus() == null ? STATUS_CLOSED : dto.getStatus();
        if (targetStatus != STATUS_PROCESSING && targetStatus != STATUS_JUDGED
                && targetStatus != STATUS_CLOSED && targetStatus != STATUS_APPEAL) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "工单状态只能是 2 处理中 / 3 已判定 / 4 申诉中 / 5 已结案");
        }
        LocalDateTime now = LocalDateTime.now();
        ReportTicket update = new ReportTicket();
        update.setId(id);
        update.setStatus(targetStatus);
        update.setDecision(dto.getDecision());
        update.setPenalty(dto.getPenalty());
        update.setHandlerId(operatorId);
        if (before.getFirstReplyAt() == null) {
            update.setFirstReplyAt(now);
        }
        if (targetStatus == STATUS_CLOSED) {
            update.setClosedAt(now);
        }
        reportMapper.updateById(update);
        auditLogService.record(operatorId, "report", "report-handle", "report_ticket", id,
                state("status", before.getStatus()), state("status", targetStatus,
                        "decision", dto.getDecision(), "penalty", dto.getPenalty()));
        log.info("[admin][report][{}] 处置 status {} -> {} operator={}", id, before.getStatus(), targetStatus, operatorId);
        ReportTicket after = reportMapper.selectById(id);
        return toVO(after, resolveNicknames(List.of(after)));
    }

    /* ------------------------------------------------------------------ */

    private Map<Long, String> resolveNicknames(Collection<ReportTicket> tickets) {
        Map<Long, String> map = new HashMap<>();
        List<Long> ids = tickets.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getReporterId(), t.getHandlerId()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return map;
        }
        try {
            var result = userClient.batchUsers(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
            if (result != null && result.getCode() == 0 && result.getData() != null) {
                for (Map<String, Object> user : result.getData()) {
                    Object id = user.get("id");
                    Object nickname = user.get("nickname");
                    if (id instanceof Number n && nickname != null) {
                        map.put(n.longValue(), String.valueOf(nickname));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[admin][report] 补充用户昵称失败（容忍）：{}", e.getMessage());
        }
        return map;
    }

    private ReportTicketVO toVO(ReportTicket e, Map<Long, String> nicknames) {
        ReportTicketVO vo = new ReportTicketVO();
        vo.setId(e.getId());
        vo.setReporterId(e.getReporterId());
        vo.setReporterNickname(e.getReporterId() == null ? null : nicknames.get(e.getReporterId()));
        vo.setTargetType(e.getTargetType());
        vo.setTargetTypeText(AdminDicts.reportTargetText(e.getTargetType()));
        vo.setTargetId(e.getTargetId());
        vo.setReportType(e.getReportType());
        vo.setReportTypeText(AdminDicts.reportTypeText(e.getReportType()));
        vo.setDescription(e.getDescription());
        vo.setImages(e.getImages());
        vo.setEvidence(e.getEvidence());
        vo.setAiSummary(e.getAiSummary());
        vo.setAiSuggest(e.getAiSuggest());
        vo.setStatus(e.getStatus());
        vo.setStatusText(AdminDicts.reportStatusText(e.getStatus()));
        vo.setDecision(e.getDecision());
        vo.setPenalty(e.getPenalty());
        vo.setHandlerId(e.getHandlerId());
        vo.setHandlerNickname(e.getHandlerId() == null ? null : nicknames.get(e.getHandlerId()));
        vo.setFirstReplyAt(e.getFirstReplyAt());
        vo.setClosedAt(e.getClosedAt());
        vo.setCreatedAt(e.getCreatedAt());
        vo.setUpdatedAt(e.getUpdatedAt());
        return vo;
    }

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 待受理数量（dashboard pendingReports，可选） */
    public long pendingCount() {
        Long count = reportMapper.selectCount(Wrappers.<ReportTicket>lambdaQuery()
                .eq(ReportTicket::getStatus, STATUS_PENDING));
        return count == null ? 0L : count;
    }
}
