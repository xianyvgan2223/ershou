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
import com.ershou.campus.module.admin.dto.PointStatusDTO;
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
    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;

    private final TradePointMapper pointMapper;
    private final AuditLogService auditLogService;
    private final AdminViewAssembler assembler;
    private final CacheEvictor evictor;

    /**
     * 点位列表。
     *
     * @param auditStatus 审核状态；传负数表示「全部」（运营要做事后处置时，待审队列之外也要能翻）
     * @param status      地图启用状态（1 启用 / 0 已下架）；null 表示不过滤
     */
    public PageResult<AdminPointVO> page(Integer auditStatus, Long campusId, String keyword, Integer status,
                                         long pageNum, long pageSize) {
        LambdaQueryWrapper<TradePoint> w = Wrappers.<TradePoint>lambdaQuery();
        if (auditStatus != null && auditStatus >= AUDIT_PENDING) {
            w.eq(TradePoint::getAuditStatus, auditStatus);
        }
        if (campusId != null) {
            w.eq(TradePoint::getCampusId, campusId);
        }
        if (status != null) {
            w.eq(TradePoint::getStatus, status);
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

    /**
     * 启用 / 停用（下架）点位：审核通过后的点位在地图上的开关，停用必须留原因。
     * 之前点位一旦通过就没有下架出口，违规点位只能一直挂在地图上。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long pointId, PointStatusDTO dto, Long operatorId) {
        Integer status = dto.getStatus();
        if (status == null || (status != STATUS_DISABLED && status != STATUS_ENABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位状态只能是 0（停用）或 1（启用）");
        }
        TradePoint before = pointMapper.selectById(pointId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位不存在");
        }
        if (status.equals(before.getStatus())) {
            throw new BizException(ResultCode.ADMIN_POINT_STATUS,
                    status == STATUS_DISABLED ? "该点位已经是停用状态" : "该点位已经是启用状态");
        }
        String reason = StringUtils.hasText(dto.getReason()) ? dto.getReason().trim() : null;
        if (status == STATUS_DISABLED && reason == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "下架点位必须填写原因");
        }
        TradePoint update = new TradePoint();
        update.setStatus(status);
        int rows = pointMapper.update(update, Wrappers.<TradePoint>lambdaUpdate()
                .eq(TradePoint::getId, pointId));
        if (rows == 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位不存在");
        }
        // 停用 / 启用会让点位在地图上消失或出现：字典缓存与地图缓存都要失效
        TxHooks.afterCommit(() -> evictor.pointChanged(pointId));
        String action = status == STATUS_DISABLED ? "point-offline" : "point-online";
        auditLogService.record(operatorId, "point", action, "trade_point", pointId,
                state("status", before.getStatus(), "name", before.getName(),
                        "auditStatus", before.getAuditStatus()),
                state("status", status, "reason", reason));
        log.info("[admin][point][{}][{}][operator={}]", pointId, action, operatorId);
    }

    private static Map<String, Object> state(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
