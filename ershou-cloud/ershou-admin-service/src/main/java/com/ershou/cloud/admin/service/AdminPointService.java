package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.MapClient;
import com.ershou.cloud.admin.client.UserClient;
import com.ershou.cloud.admin.config.AdminProperties;
import com.ershou.cloud.admin.dto.PointApproveDTO;
import com.ershou.cloud.admin.dto.PointRejectDTO;
import com.ershou.cloud.admin.dto.PointStatusDTO;
import com.ershou.cloud.admin.vo.AdminPointVO;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 面交点审核/启停/清理（BFF → map-service /inner/points）。
 *
 * <p>map-service 的 inner 列表接口是"全量 + campusId/status 可选过滤"，没有分页/auditStatus/keyword 参数，
 * 所以管理端的审核状态、关键词筛选与分页在本服务本地完成（点位数量级小，演示可接受；
 * 若未来点位上万，需要 map-service 扩展 inner 分页接口，已列入遗留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPointService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_DISABLED = 0;
    private static final int STATUS_ENABLED = 1;

    private final MapClient mapClient;
    private final UserClient userClient;
    private final AdminProperties adminProperties;
    private final AuditLogService auditLogService;

    /** 点位列表：auditStatus 为空 = 不限审核状态；keyword 在名称/指引中模糊匹配 */
    public PageResult<AdminPointVO> page(Integer auditStatus, Long campusId, String keyword, Integer status,
                                         long pageNum, long pageSize) {
        List<Map<String, Object>> raw = FeignResults.data(mapClient.listPoints(campusId, status), "地图服务");
        Map<Long, String> campusNames = campusNamesTolerant();
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase();
        List<AdminPointVO> all = raw == null ? List.of()
                : raw.stream().map(m -> toVO(m, campusNames)).toList();
        List<AdminPointVO> filtered = all.stream()
                .filter(p -> auditStatus == null || Objects.equals(p.getAuditStatus(), auditStatus))
                .filter(p -> kw == null || matches(p, kw))
                .toList();
        long total = filtered.size();
        long page = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int from = (int) Math.min((page - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        return PageResult.of(filtered.subList(from, to), total, page, size);
    }

    /** 点位审核通过（map 只接收 action；安全分/指引/标签由本服务记审计，跨服务落库已列遗留） */
    public void approve(Long pointId, PointApproveDTO dto, Long operatorId) {
        FeignResults.data(mapClient.auditPoint(pointId, "approve"), "地图服务");
        auditLogService.record(operatorId, "point", "approve", "trade_point", pointId, null,
                state("auditStatus", 1, "safetyScore", dto.getSafetyScore(),
                        "guideText", dto.getGuideText(), "safeTags", dto.getSafeTags()));
        log.info("[admin][point][{}][approve][operator={}]", pointId, operatorId);
    }

    /** 点位审核驳回（驳回原因必填：模板或补充说明；原因保留在 admin 审计日志） */
    public void reject(Long pointId, PointRejectDTO dto, Long operatorId) {
        String reason = AuditReasons.compose(AuditReasons.SCOPE_POINT, dto.getReasonCode(), dto.getReason());
        FeignResults.data(mapClient.auditPoint(pointId, "reject"), "地图服务");
        auditLogService.record(operatorId, "point", "reject", "trade_point", pointId, null,
                state("auditStatus", 2, "reasonCode", dto.getReasonCode(), "reason", reason));
        log.info("[admin][point][{}][reject][{}][operator={}]", pointId, dto.getReasonCode(), operatorId);
    }

    /** 启用/停用（下架）点位：停用必须留原因（原因保留在 admin 审计日志） */
    public void updateStatus(Long pointId, PointStatusDTO dto, Long operatorId) {
        Integer status = dto.getStatus();
        if (status == null || (status != STATUS_DISABLED && status != STATUS_ENABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位状态只能是 0（停用）或 1（启用）");
        }
        String reason = dto.getReason() == null ? null : dto.getReason().trim();
        if (status == STATUS_DISABLED && (reason == null || reason.isEmpty())) {
            throw new BizException(ResultCode.PARAM_ERROR, "下架点位必须填写原因");
        }
        FeignResults.data(mapClient.updatePointStatus(pointId, status), "地图服务");
        auditLogService.record(operatorId, "point", status == STATUS_DISABLED ? "point-offline" : "point-online",
                "trade_point", pointId, null, state("status", status, "reason", reason));
    }

    /** 清理没有商品的面交点（立即执行；日常由 map-service 定时任务 03:10 跑） */
    public int cleanupEmptyPoints(Long operatorId) {
        Integer removed = FeignResults.data(mapClient.cleanupEmptyPoints(), "地图服务");
        int count = removed == null ? 0 : removed;
        auditLogService.record(operatorId, "point", "cleanup-empty", "trade_point", null, null,
                state("removed", count));
        log.info("[admin][point][cleanup-empty] removed={} operator={}", count, operatorId);
        return count;
    }

    /* ------------------------------------------------------------------ */

    private static boolean matches(AdminPointVO p, String kw) {
        return contains(p.getName(), kw) || contains(p.getGuideText(), kw)
                || contains(p.getCampusName(), kw);
    }

    private static boolean contains(String text, String kw) {
        return text != null && text.toLowerCase().contains(kw);
    }

    /** campusId → campusName（map inner 只给 id；校区字典在 user-service） */
    private Map<Long, String> campusNamesTolerant() {
        Map<Long, String> map = new HashMap<>();
        try {
            List<Map<String, Object>> campuses = FeignResults.data(userClient.campuses(null), "用户服务");
            if (campuses != null) {
                for (Map<String, Object> campus : campuses) {
                    Long id = MapHelper.lng(campus, "id");
                    if (id != null) {
                        map.put(id, MapHelper.str(campus, "name"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[admin][point] 补充校区名失败（容忍）：{}", e.getMessage());
        }
        return map;
    }

    private AdminPointVO toVO(Map<String, Object> m, Map<Long, String> campusNames) {
        if (m == null) {
            return null;
        }
        AdminPointVO vo = new AdminPointVO();
        vo.setId(MapHelper.lng(m, "id"));
        vo.setCampusId(MapHelper.lng(m, "campusId"));
        vo.setCampusName(campusNames.get(vo.getCampusId()));
        vo.setName(MapHelper.str(m, "name"));
        vo.setSource(MapHelper.intg(m, "source"));
        // map inner 的字段名：type / longitude / latitude / guide（与 admin VO 的语义一一对应）
        vo.setPointType(MapHelper.intg(m, "type"));
        vo.setCoordX(MapHelper.dec(m, "coordX"));
        vo.setCoordY(MapHelper.dec(m, "coordY"));
        vo.setLng(MapHelper.dec(m, "longitude"));
        vo.setLat(MapHelper.dec(m, "latitude"));
        vo.setSafetyScore(MapHelper.intg(m, "safetyScore"));
        vo.setSafeTags(MapHelper.strList(m, "safeTags"));
        vo.setGuideText(MapHelper.str(m, "guide"));
        vo.setStatus(MapHelper.intg(m, "status"));
        vo.setAuditStatus(MapHelper.intg(m, "auditStatus"));
        // 创建人信息 map inner 暂未下发（遗留）
        vo.setCreatorId(null);
        vo.setCreatorNickname(null);
        vo.setCreatedAt(MapHelper.dt(m, "createdAt"));
        return vo;
    }

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 待审点位数（dashboard 兜底；当前直接读 map stats） */
    public long pendingCountTolerant() {
        try {
            Map<String, Object> stats = FeignResults.data(mapClient.pointStats(), "地图服务");
            Long pending = MapHelper.lng(stats, "pending");
            return pending == null ? 0L : pending;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 点位状态可配置的管理员标记（供未来扩展，避免编译告警） */
    @SuppressWarnings("unused")
    private int adminRoleFlag() {
        return adminProperties.getRoleFlag();
    }
}
