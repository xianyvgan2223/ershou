package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.UserClient;
import com.ershou.cloud.admin.dto.CreditAdjustDTO;
import com.ershou.cloud.admin.dto.UserStatusDTO;
import com.ershou.cloud.admin.vo.AdminUserVO;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 用户管理（BFF → user-service /inner/users）。
 *
 * <p>本服务不存用户数据、不跨库；封禁/信用分由 user-service 落库并写它自己的 credit_log，
 * 本服务只负责：参数校验、调用、把结果映射成与旧单体一致的 AdminUserVO、写运营审计日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int CREDIT_MAX_DELTA = 100;

    private final UserClient userClient;
    private final AuditLogService auditLogService;

    public PageResult<AdminUserVO> page(String keyword, Integer status, long pageNum, long pageSize) {
        PageResult<Map<String, Object>> res = FeignResults.data(
                userClient.pageUsers(keyword == null ? "" : keyword.trim(), status,
                        Math.max(pageNum, 1), clamp(pageSize)), "用户服务");
        if (res == null) {
            return PageResult.of(List.of(), 0, Math.max(pageNum, 1), clamp(pageSize));
        }
        List<AdminUserVO> list = res.getRecords() == null ? List.of()
                : res.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, res.getTotal(), res.getPageNum(), res.getPageSize());
    }

    public AdminUserVO updateStatus(Long userId, UserStatusDTO dto, Long operatorId) {
        int status = dto.getStatus();
        if (status < 1 || status > 3) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户状态只能是 1 正常 / 2 限权 / 3 封禁");
        }
        if (Objects.equals(userId, operatorId)) {
            throw new BizException(ResultCode.ADMIN_SELF_OPERATION, "不能修改自己的账号状态");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (status == 3 && dto.getBanDays() != null && dto.getBanDays() > 0) {
            body.put("banDays", dto.getBanDays());
        }
        body.put("reason", dto.getReason());
        body.put("operatorId", operatorId);
        Map<String, Object> updated = FeignResults.data(userClient.updateStatus(userId, body), "用户服务");
        auditLogService.record(operatorId, "user", "update-status", "user", userId, null,
                state("status", status, "banDays", dto.getBanDays(), "reason", dto.getReason()));
        return updated == null ? loadUser(userId) : toVO(updated);
    }

    public AdminUserVO adjustCredit(Long userId, CreditAdjustDTO dto, Long operatorId) {
        int delta = dto.getChangeValue();
        if (delta == 0 || Math.abs(delta) > CREDIT_MAX_DELTA) {
            throw new BizException(ResultCode.PARAM_ERROR, "单次调整范围为 -100 ~ +100，且不能为 0");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // user-service 的 CreditAdjustDTO 只认 delta/reason（多余键会被忽略）
        body.put("delta", delta);
        body.put("reason", dto.getReason());
        Integer affected = FeignResults.data(userClient.adjustCredit(userId, body), "用户服务");
        if (affected != null && affected == 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户不存在或信用分未变化");
        }
        auditLogService.record(operatorId, "user", "adjust-credit", "user", userId, null,
                state("change", delta, "reason", dto.getReason()));
        // credit inner 只返回受影响行数，详情重新查（phone/风控字段由管理列表接口提供）
        return loadUser(userId);
    }

    /** user-service 单用户（dashboard/详情兜底用） */
    public AdminUserVO loadUser(Long userId) {
        return toVO(FeignResults.data(userClient.getUser(userId), "用户服务"));
    }

    /* ------------------------------------------------------------------ */

    private static long clamp(long pageSize) {
        return Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }

    private AdminUserVO toVO(Map<String, Object> u) {
        if (u == null) {
            return null;
        }
        AdminUserVO vo = new AdminUserVO();
        vo.setId(MapHelper.lng(u, "id"));
        vo.setNickname(MapHelper.str(u, "nickname"));
        vo.setAvatar(MapHelper.str(u, "avatar"));
        vo.setPhone(MapHelper.str(u, "phone"));
        vo.setSchoolId(MapHelper.lng(u, "schoolId"));
        vo.setCampusId(MapHelper.lng(u, "campusId"));
        vo.setShopName(MapHelper.str(u, "shopName"));
        vo.setRoleFlags(MapHelper.intg(u, "roleFlags"));
        vo.setIdentityFlag(MapHelper.intg(u, "identityFlag"));
        vo.setCreditScore(MapHelper.intg(u, "creditScore"));
        vo.setCreditLevel(MapHelper.intg(u, "creditLevel"));
        vo.setCreditLevelText(AdminDicts.creditLevelText(vo.getCreditLevel()));
        vo.setRiskScore(MapHelper.intg(u, "riskScore"));
        vo.setStatus(MapHelper.intg(u, "status"));
        vo.setStatusText(AdminDicts.userStatusText(vo.getStatus()));
        vo.setBanUntil(MapHelper.dt(u, "banUntil"));
        vo.setPublishLimit(MapHelper.intg(u, "publishLimit"));
        vo.setChatLimit(MapHelper.intg(u, "chatLimit"));
        vo.setDeviceId(MapHelper.str(u, "deviceId"));
        vo.setDeviceAccountCount(MapHelper.intg(u, "deviceAccountCount"));
        vo.setLastLoginAt(MapHelper.dt(u, "lastLoginAt"));
        vo.setCreatedAt(MapHelper.dt(u, "createdAt"));
        return vo;
    }

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
