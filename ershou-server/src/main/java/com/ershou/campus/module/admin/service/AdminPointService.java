package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.admin.dto.PointApproveDTO;
import com.ershou.campus.module.admin.dto.PointRejectDTO;
import com.ershou.campus.module.admin.vo.AdminPointVO;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面交点审核：用户提交的点位（audit_status=0）在这里通过或驳回。
 * 之前该状态没有出口，用户提点会永久停在待审。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPointService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int AUDIT_PENDING = 0;
    private static final int AUDIT_APPROVED = 1;
    private static final int AUDIT_REJECTED = 2;

    private final TradePointMapper pointMapper;
    private final AuditLogService auditLogService;
    private final AdminViewAssembler assembler;
    private final CacheEvictor evictor;

    public PageResult<AdminPointVO> page(Integer auditStatus, Long campusId, String keyword,
                                         long pageNum, long pageSize) {
        LambdaQueryWrapper<TradePoint> w = Wrappers.<TradePoint>lambdaQuery();
        if (auditStatus != null) {
            w.eq(TradePoint::getAuditStatus, auditStatus);
        }
        if (campusId != null) {
            w.eq(TradePoint::getCampusId, campusId);
        }
        if (StringUtils.hasText(keyword)) {
            w.like(TradePoint::getName, keyword.trim());
        }
        if (auditStatus != null && auditStatus == AUDIT_PENDING) {
            w.orderByAsc(TradePoint::getCreatedAt);
        } else {
            w.orderByDesc(TradePoint::getCreatedAt).orderByDesc(TradePoint::getId);
        }
        Page<TradePoint> page = pointMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return assembler.pointPage(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long pointId, PointApproveDTO dto, Long operatorId) {
        TradePoint before = pointMapper.selectById(pointId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位不存在");
        }
        TradePoint update = new TradePoint();
        update.setAuditStatus(AUDIT_APPROVED);
        update.setStatus(1);
        if (dto.getSafetyScore() != null) {
            update.setSafetyScore(Math.max(0, Math.min(100, dto.getSafetyScore())));
        }
        if (StringUtils.hasText(dto.getGuideText())) {
            update.setGuideText(dto.getGuideText().trim());
        }
        if (dto.getSafeTags() != null) {
            update.setSafeTags(assembler.writeStringList(dto.getSafeTags()));
        }
        int rows = pointMapper.update(update, Wrappers.<TradePoint>lambdaUpdate()
                .eq(TradePoint::getId, pointId)
                .eq(TradePoint::getAuditStatus, AUDIT_PENDING));
        if (rows == 0) {
            throw new BizException(ResultCode.ADMIN_POINT_STATUS, "仅“待审核”的点位可以通过");
        }
        // 点位通过后会出现在地图上：字典缓存与地图缓存都要失效
        TxHooks.afterCommit(() -> evictor.pointChanged(pointId));
        auditLogService.record(operatorId, "point", "approve", "trade_point", pointId,
                state("auditStatus", before.getAuditStatus(), "safetyScore", before.getSafetyScore()),
                state("auditStatus", AUDIT_APPROVED, "safetyScore", update.getSafetyScore(),
                        "guideText", update.getGuideText(), "safeTags", dto.getSafeTags()));
        log.info("[admin][point][{}][approve][operator={}]", pointId, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long pointId, PointRejectDTO dto, Long operatorId) {
        TradePoint before = pointMapper.selectById(pointId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位不存在");
        }
        String reason = AuditReasons.compose(AuditReasons.SCOPE_POINT, dto.getReasonCode(), dto.getReason());
        TradePoint update = new TradePoint();
        update.setAuditStatus(AUDIT_REJECTED);
        int rows = pointMapper.update(update, Wrappers.<TradePoint>lambdaUpdate()
                .eq(TradePoint::getId, pointId)
                .eq(TradePoint::getAuditStatus, AUDIT_PENDING));
        if (rows == 0) {
            throw new BizException(ResultCode.ADMIN_POINT_STATUS, "仅“待审核”的点位可以驳回");
        }
        TxHooks.afterCommit(() -> evictor.pointChanged(pointId));
        auditLogService.record(operatorId, "point", "reject", "trade_point", pointId,
                state("auditStatus", before.getAuditStatus(), "name", before.getName()),
                state("auditStatus", AUDIT_REJECTED, "reasonCode", dto.getReasonCode(), "reason", reason));
        log.info("[admin][point][{}][reject][{}][operator={}]", pointId, dto.getReasonCode(), operatorId);
    }

    private static Map<String, Object> state(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
