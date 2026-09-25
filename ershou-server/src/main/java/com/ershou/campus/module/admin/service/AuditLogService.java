package com.ershou.campus.module.admin.service;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogMapper auditLogMapper;
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
