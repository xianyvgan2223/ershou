package com.ershou.cloud.common.web;

import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.ResultCode;

/**
 * 当前登录用户上下文（ThreadLocal）。
 *
 * <p>微服务内部不各自解析 token：网关统一鉴权后注入 {@code X-User-Id}，
 * 服务里的 {@link UserContextInterceptor} 在请求进入时把 header 写入本上下文，
 * 业务代码用 {@link #getUserId()} / {@link #requireUserId()} 取值，请求结束自动清理。
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    /** 当前用户 id；未登录（网关未注入/匿名接口）返回 null */
    public static Long getUserId() {
        return USER_ID.get();
    }

    /** 当前用户 id；要求必须登录，否则抛 10002 */
    public static Long requireUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        return userId;
    }

    public static boolean isLogin() {
        return USER_ID.get() != null;
    }

    public static void clear() {
        USER_ID.remove();
    }
}
