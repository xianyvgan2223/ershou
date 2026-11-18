package com.ershou.cloud.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.admin.entity.SysAuditLog;
import com.ershou.cloud.admin.mapper.SysAuditLogMapper;
import com.ershou.cloud.admin.vo.AuditLogVO;
import com.ershou.cloud.common.result.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 操作审计日志查询（本服务库 ershou_admin.sys_audit_log）。
 *
 * <p>时间参数宽松解析（支持 {@code yyyy-MM-dd} / {@code yyyy-MM-dd HH:mm:ss} / ISO），
 * 解析失败忽略该条件 —— 与单体行为一致，不能因为一个脏参数让整个审计页报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int DATE_LENGTH = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SysAuditLogMapper auditLogMapper;
    private final ObjectMapper json;

    public PageResult<AuditLogVO> page(String module, Long operatorId, String action,
                                       String startTime, String endTime,
                                       long pageNum, long pageSize) {
        LambdaQueryWrapper<SysAuditLog> w = Wrappers.<SysAuditLog>lambdaQuery();
        if (StringUtils.hasText(module)) {
            w.eq(SysAuditLog::getModule, module.trim());
        }
        if (operatorId != null) {
            w.eq(SysAuditLog::getOperatorId, operatorId);
        }
        if (StringUtils.hasText(action)) {
            w.eq(SysAuditLog::getAction, action.trim());
        }
        LocalDateTime from = parseTime(startTime, false);
        if (from != null) {
            w.ge(SysAuditLog::getCreatedAt, from);
        }
        LocalDateTime to = parseTime(endTime, true);
        if (to != null) {
            w.le(SysAuditLog::getCreatedAt, to);
        }
        w.orderByDesc(SysAuditLog::getId);
        Page<SysAuditLog> page = auditLogMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return PageResult.of(page, this::toVO);
    }

    /** 今日操作数（dashboard 指标 todayOperations；统计本服务自己的审计表，不跨库） */
    public long todayOperations() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Long count = auditLogMapper.selectCount(Wrappers.<SysAuditLog>lambdaQuery()
                .ge(SysAuditLog::getCreatedAt, todayStart));
        return count == null ? 0L : count;
    }

    /**
     * 批量补充操作人昵称（运营自己人，字段敏感度低；user-service 不可用时容忍为空）。
     * 目前直接返回空 map，由 Controller/前端用 operatorId 兜底展示；保留钩子便于后续接 user-service。
     */
    public Map<Long, String> operatorNicknames(List<Long> operatorIds) {
        return new HashMap<>();
    }

    private AuditLogVO toVO(SysAuditLog log) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(log.getId());
        vo.setOperatorId(log.getOperatorId());
        vo.setModule(log.getModule());
        vo.setAction(log.getAction());
        vo.setTargetType(log.getTargetType());
        vo.setTargetId(log.getTargetId());
        vo.setBeforeValue(parse(log.getBeforeValue()));
        vo.setAfterValue(parse(log.getAfterValue()));
        vo.setIp(log.getIp());
        vo.setCreatedAt(log.getCreatedAt());
        return vo;
    }

    /** 库里存 JSON 字符串，返回前端还原为对象；解析失败原样返回 */
    private Object parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return json.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private static LocalDateTime parseTime(String raw, boolean end) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = raw.trim();
        try {
            if (text.length() == DATE_LENGTH) {
                LocalDate date = LocalDate.parse(text, DATE);
                return end ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
            return LocalDateTime.parse(text, text.indexOf('T') >= 0 ? DATE_TIME_ISO : DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** 供其他 service 生成审计快照用（保持 JSON 一致） */
    @SuppressWarnings("unused")
    private static boolean same(Long a, Long b) {
        return Objects.equals(a, b);
    }
}
