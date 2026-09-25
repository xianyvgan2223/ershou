package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.PhoneCrypto;
import com.ershou.campus.module.admin.dto.CreditAdjustDTO;
import com.ershou.campus.module.admin.dto.UserStatusDTO;
import com.ershou.campus.module.admin.entity.CreditLog;
import com.ershou.campus.module.admin.mapper.CreditLogMapper;
import com.ershou.campus.module.admin.vo.AdminUserVO;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 用户管理：查询、封禁/解封、信用分人工调整（留 credit_log 流水 + 审计日志）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_BANNED = 3;
    private static final int CREDIT_MAX_DELTA = 100;

    private final SysUserMapper userMapper;
    private final CreditLogMapper creditLogMapper;
    private final PhoneCrypto phoneCrypto;
    private final AuditLogService auditLogService;
    private final AdminViewAssembler assembler;

    public PageResult<AdminUserVO> page(String keyword, Integer status, long pageNum, long pageSize) {
        LambdaQueryWrapper<SysUser> w = Wrappers.<SysUser>lambdaQuery();
        if (status != null) {
            w.eq(SysUser::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            if (kw.matches("^1\\d{10}$")) {
                // 手机号密文存储，按 SHA256 精确命中
                w.eq(SysUser::getPhoneHash, phoneCrypto.hash(kw));
            } else {
                w.and(x -> x.like(SysUser::getNickname, kw).or().like(SysUser::getShopName, kw));
            }
        }
        w.orderByDesc(SysUser::getId);
        Page<SysUser> page = userMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return assembler.userPage(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO updateStatus(Long userId, UserStatusDTO dto, Long operatorId) {
        if (Objects.equals(userId, operatorId)) {
            throw new BizException(ResultCode.ADMIN_SELF_OPERATION, "不能修改自己的账号状态");
        }
        SysUser before = userMapper.selectById(userId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户不存在");
        }
        int status = dto.getStatus();
        if (status < 1 || status > 3) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户状态只能是 1 正常 / 2 限权 / 3 封禁");
        }
        LocalDateTime banUntil = null;
        if (status == STATUS_BANNED && dto.getBanDays() != null && dto.getBanDays() > 0) {
            banUntil = LocalDateTime.now().plusDays(dto.getBanDays());
        }
        // banUntil 需要能被显式置空，用 update wrapper 而不是 updateById（null 字段会被忽略）
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .set(SysUser::getStatus, status)
                .set(SysUser::getBanUntil, banUntil));
        auditLogService.record(operatorId, "user", "update-status", "user", userId,
                state("status", before.getStatus(), "banUntil", before.getBanUntil()),
                state("status", status, "banUntil", banUntil, "reason", dto.getReason()));
        log.info("[admin][user][{}][status={}][operator={}]", userId, status, operatorId);
        return assembler.toUserVO(userMapper.selectById(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public AdminUserVO adjustCredit(Long userId, CreditAdjustDTO dto, Long operatorId) {
        SysUser before = userMapper.selectById(userId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户不存在");
        }
        int delta = dto.getChangeValue();
        if (delta == 0 || Math.abs(delta) > CREDIT_MAX_DELTA) {
            throw new BizException(ResultCode.PARAM_ERROR, "单次调整范围为 -100 ~ +100，且不能为 0");
        }
        int beforeScore = before.getCreditScore() == null ? 100 : before.getCreditScore();
        int after = Math.max(0, beforeScore + delta);
        userMapper.setCredit(userId, after, creditLevelOf(after));

        CreditLog creditLog = new CreditLog();
        creditLog.setUserId(userId);
        creditLog.setChangeValue(after - beforeScore);
        creditLog.setBeforeScore(beforeScore);
        creditLog.setAfterScore(after);
        creditLog.setReason(StringUtils.hasText(dto.getReason()) ? dto.getReason().trim() : "运营人工调整");
        creditLog.setBizType("admin");
        creditLog.setOperatorId(operatorId);
        creditLogMapper.insert(creditLog);

        auditLogService.record(operatorId, "user", "adjust-credit", "user", userId,
                state("creditScore", beforeScore),
                state("creditScore", after, "change", after - beforeScore, "reason", creditLog.getReason()));
        log.info("[admin][user][{}][credit {} -> {}][operator={}]", userId, beforeScore, after, operatorId);
        return assembler.toUserVO(userMapper.selectById(userId));
    }

    public static int creditLevelOf(int score) {
        if (score >= 95) {
            return 1;
        }
        if (score >= 80) {
            return 2;
        }
        return score >= 60 ? 3 : 4;
    }

    private static Map<String, Object> state(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
