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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 运营操作审计日志查询 */
@Service
@RequiredArgsConstructor
public class AdminLogService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SysAuditLogMapper auditLogMapper;
    private final UserService userService;
    private final ObjectMapper json;

    public PageResult<AuditLogVO> page(String module, Long operatorId, long pageNum, long pageSize) {
        LambdaQueryWrapper<SysAuditLog> w = Wrappers.<SysAuditLog>lambdaQuery();
        if (StringUtils.hasText(module)) {
            w.eq(SysAuditLog::getModule, module.trim());
        }
        if (operatorId != null) {
            w.eq(SysAuditLog::getOperatorId, operatorId);
        }
        w.orderByDesc(SysAuditLog::getId);
        Page<SysAuditLog> page = auditLogMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        List<Long> operatorIds = page.getRecords().stream().map(SysAuditLog::getOperatorId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, SysUser> operators = userService.mapByIds(operatorIds);
        return PageResult.of(page, log -> toVO(log, operators));
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
