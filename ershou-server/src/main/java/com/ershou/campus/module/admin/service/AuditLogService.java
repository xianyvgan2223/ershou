package com.ershou.campus.module.admin.service;

import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.admin.entity.SysAuditLog;
import com.ershou.campus.module.admin.mapper.SysAuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 运营操作审计：所有后台写操作都落一条 sys_audit_log。
 * 审计失败只告警、不影响主流程。
 *
 * <p>这里同时充当后台写操作的统一出口：概览统计（30 秒缓存）在每次写操作后删掉，
 * 免得运营审核完商品后侧栏待办角标还要等缓存过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogMapper auditLogMapper;
    private final CacheEvictor cacheEvictor;
    private final ObjectMapper json;

    public void record(Long operatorId, String module, String action,
                       String targetType, Long targetId, Object before, Object after) {
        try {
            SysAuditLog entity = new SysAuditLog();
            entity.setOperatorId(operatorId);
            entity.setModule(module);
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setBeforeValue(toJson(before));
            entity.setAfterValue(toJson(after));
            entity.setIp(currentIp());
            auditLogMapper.insert(entity);
            // 写操作已落库，工作台待办数字就变了：删缓存让它下次重算（失败只记日志）。
            // 必须等事务提交后再删 —— record() 几乎都在 @Transactional 方法里被调用，
            // 提前删会被并发读用旧值回填，缓存反而更脏（见 CacheEvictor 的类注释）。
            TxHooks.afterCommit(cacheEvictor::adminDashboard);
        } catch (Exception e) {
            log.warn("[audit-log] 写入失败 module={} action={} target={}#{}：{}",
                    module, action, targetType, targetId, e.getMessage());
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String currentIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest request = attrs.getRequest();
                String forwarded = request.getHeader("X-Forwarded-For");
                if (StringUtils.hasText(forwarded)) {
                    return forwarded.split(",")[0].trim();
                }
                return request.getRemoteAddr();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
