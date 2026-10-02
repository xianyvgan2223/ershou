package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.module.admin.entity.SysAuditLog;
import com.ershou.campus.module.admin.mapper.SysAuditLogMapper;
import com.ershou.campus.module.admin.vo.AuditLogVO;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 运营操作审计日志查询 */
@Service
@RequiredArgsConstructor
public class AdminLogService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final int DATE_LENGTH = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SysAuditLogMapper auditLogMapper;
    private final UserService userService;
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
        List<Long> operatorIds = page.getRecords().stream().map(SysAuditLog::getOperatorId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, SysUser> operators = userService.mapByIds(operatorIds);
        return PageResult.of(page, log -> toVO(log, operators));
    }

    /**
     * 宽松解析前端传的时间：支持 {@code yyyy-MM-dd HH:mm:ss}、ISO（{@code yyyy-MM-dd'T'HH:mm:ss}）
     * 以及只给日期 {@code yyyy-MM-dd}。
     *
     * <p>只给日期时，开始时间补 00:00:00、结束时间补 23:59:59，否则"查当天"会查不到数据。
     * 解析失败返回 null，即忽略这个条件，不因为一个脏参数让整个列表报错。
     */
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

    private AuditLogVO toVO(SysAuditLog log, Map<Long, SysUser> operators) {
        AuditLogVO vo = new AuditLogVO();
        vo.setId(log.getId());
        vo.setOperatorId(log.getOperatorId());
        SysUser operator = log.getOperatorId() == null ? null : operators.get(log.getOperatorId());
        if (operator != null) {
            vo.setOperatorNickname(operator.getNickname());
        }
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

    /** 库里存的是 JSON 字符串，返回给前端时还原成对象；解析失败则原样返回 */
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
}
