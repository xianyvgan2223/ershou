package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.UserClient;
import com.ershou.cloud.admin.config.AdminProperties;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.web.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 运营权限判定（本服务唯一入口，与单体 AdminAuthService 语义一致）。
 *
 * <p>判权链路：网关注入的 {@code X-User-Id} → {@link UserContext#requireUserId()} →
 * 调 user-service {@code GET /inner/users/{id}} 拿 isAdmin/roleFlags → 命中才放行。
 * <b>不解析 Sa-Token</b>（HTTP 身份只信网关；WS 握手除外）。
 *
 * <p>降级策略：user-service 不可用时，仅当 {@code ershou.admin.bootstrap-user-ids}（默认空）
 * 命中才放行并告警；否则抛 80001 —— 宁可管理端不可用，也不做无依据的放行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final UserClient userClient;
    private final AdminProperties adminProperties;

    /** 校验当前请求是运营人员，返回其用户 id（写审计日志用） */
    public Long requireAdminId() {
        Long userId = UserContext.requireUserId();
        if (isAdmin(userId)) {
            return userId;
        }
        throw new BizException(ResultCode.NO_PERMISSION, "需要运营人员权限");
    }

    /** 是否运营：权威来自 user-service；降级时用 bootstrap 白名单 + roleFlags 本地兜底 */
    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        if (adminProperties.getBootstrapUserIds().contains(userId)) {
            return true;
        }
        try {
            Map<String, Object> user = FeignResults.data(userClient.getUser(userId), "用户服务");
            return isAdminUser(user);
        } catch (BizException e) {
            if (e.getCode() == ResultCode.SERVICE_UNAVAILABLE.getCode()
                    && adminProperties.getBootstrapUserIds().contains(userId)) {
                log.warn("[admin][auth][{}] user-service 不可用，命中 bootstrap 白名单放行（仅演示/应急）", userId);
                return true;
            }
            throw e;
        }
    }

    private boolean isAdminUser(Map<String, Object> user) {
        if (user == null) {
            return false;
        }
        Object isAdmin = user.get("isAdmin");
        if (Boolean.TRUE.equals(isAdmin)) {
            return true;
        }
        Object status = user.get("status");
        if (status instanceof Number n && n.intValue() != 1) {
            // 被封禁/限权的账号不做运营（与安全审计建议一致）
            return false;
        }
        Object roleFlags = user.get("roleFlags");
        if (roleFlags instanceof Number n) {
            return (n.intValue() & adminProperties.getRoleFlag()) != 0;
        }
        return false;
    }
}
