package com.ershou.cloud.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.admin.entity.AiAuditRecord;
import com.ershou.cloud.admin.mapper.AiAuditRecordMapper;
import com.ershou.cloud.admin.vo.AiAuditRecordVO;
import com.ershou.cloud.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 审核记录查询（ershou_admin.ai_audit_record，T107 新增只读接口）。
 *
 * <p>旧单体机审结果只写 item.audit_result + 日志、从未落这张表（历史遗留问题 #4），
 * 所以当前记录为空；接口先按"可查"实现，机审落库接入后无需改前端。
 */
@Service
@RequiredArgsConstructor
public class AdminAiAuditService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AiAuditRecordMapper mapper;

    public PageResult<AiAuditRecordVO> page(String bizType, Long bizId, Integer decision,
                                            long pageNum, long pageSize) {
        LambdaQueryWrapper<AiAuditRecord> w = Wrappers.<AiAuditRecord>lambdaQuery();
        if (StringUtils.hasText(bizType)) {
            w.eq(AiAuditRecord::getBizType, bizType.trim());
        }
        if (bizId != null) {
            w.eq(AiAuditRecord::getBizId, bizId);
        }
        if (decision != null) {
            w.eq(AiAuditRecord::getDecision, decision);
        }
        w.orderByDesc(AiAuditRecord::getId);
        Page<AiAuditRecord> page = mapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return PageResult.of(page, this::toVO);
    }

    private AiAuditRecordVO toVO(AiAuditRecord e) {
        AiAuditRecordVO vo = new AiAuditRecordVO();
        vo.setId(e.getId());
        vo.setBizType(e.getBizType());
        vo.setBizId(e.getBizId());
        vo.setModelVersion(e.getModelVersion());
        vo.setLabels(e.getLabels());
        vo.setScore(e.getScore());
        vo.setDecision(e.getDecision());
        vo.setDecisionText(decisionText(e.getDecision()));
        vo.setReason(e.getReason());
        vo.setOperatorId(e.getOperatorId());
        vo.setAppealStatus(e.getAppealStatus());
        vo.setAppealStatusText(appealText(e.getAppealStatus()));
        vo.setCostMs(e.getCostMs());
        vo.setCreatedAt(e.getCreatedAt());
        return vo;
    }

    private static String decisionText(Integer decision) {
        if (decision == null) {
            return null;
        }
        return switch (decision) {
            case 1 -> "放行";
            case 2 -> "拦截";
            case 3 -> "转人工";
            default -> "未知";
        };
    }

    private static String appealText(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 0 -> "无";
            case 1 -> "申诉中";
            case 2 -> "维持";
            case 3 -> "改判";
            default -> "未知";
        };
    }
}
