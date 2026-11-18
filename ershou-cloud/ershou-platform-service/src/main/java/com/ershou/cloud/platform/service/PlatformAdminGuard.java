package com.ershou.cloud.platform.service;

import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.platform.feign.UserClient;
import com.ershou.cloud.platform.feign.model.InnerUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 运营权限判定（云拆分版，替代单体 {@code AdminAuthService}）。
 *
 * <p>鉴权边界：网关已用 Sa-Token 校验登录并注入 {@code X-User-Id}；本服务不再解析 token，
 * 只把用户 id 通过 user-service {@code /inner/users/{id}} 换成角色位，再判定运营位。
 *
 * <p>安全约定：user-service 不可用（Feign fallback 90001/80001）时 **fail-closed** 抛「无权限/服务不可用」，
 * 绝不放行；返回的错误码沿用单体：普通用户非运营 → 10003，downstream 不可用 → 80001。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAdminGuard {

    private final UserClient userClient;

    /** 运营位（与单体 {@code ershou.admin.role-flag:8} 一致，Nacos 可覆盖） */
    @Value("${ershou.platform.admin-role-flag:8}")
    private int adminRoleFlag;

    /** 校验当前登录用户是运营人员，返回其 user id（与单体 requireAdminId 语义一致） */
    public Long requireAdminId() {
        if (!UserContext.isLogin()) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        Long userId = UserContext.requireUserId();
        Result<InnerUserVO> result = userClient.user(userId);
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            log.warn("[platform][admin] 用户服务不可用，拒绝运营接口 userId={} result={}", userId,
                    result == null ? null : result.getCode());
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "用户服务暂不可用，无法校验运营权限");
        }
        InnerUserVO user = result.getData();
        boolean admin = Boolean.TRUE.equals(user.getIsAdmin())
                || (user.getRoleFlags() != null && (user.getRoleFlags() & adminRoleFlag) != 0);
        if (!admin) {
            throw new BizException(ResultCode.NO_PERMISSION, "需要运营人员权限");
        }
        return userId;
    }
}
