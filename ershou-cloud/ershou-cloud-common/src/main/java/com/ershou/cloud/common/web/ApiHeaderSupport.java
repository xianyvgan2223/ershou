package com.ershou.cloud.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 网关透传头约定（前端不感知，只有服务间使用）。
 *
 * <ul>
 *   <li>{@code X-User-Id}：网关鉴权通过后写入的登录用户 id；服务内部用 {@link UserContext#getUserId()} 读取
 *       （拦截器已自动从 header 填充），不建议业务代码直接摸 header。</li>
 *   <li>{@code X-Trace-Id}：链路追踪 id，网关生成、服务沿用，写日志与响应体。</li>
 * </ul>
 */
public final class ApiHeaderSupport {

    public static final String X_USER_ID = "X-User-Id";
    public static final String X_TRACE_ID = "X-Trace-Id";

    private ApiHeaderSupport() {
    }

    public static String getHeader(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    /** 从请求头解析登录用户 id；缺失/非法返回 null */
    public static Long resolveUserId(HttpServletRequest request) {
        return parseUserId(getHeader(request, X_USER_ID));
    }

    /** 字符串 user id → Long；空串/非法返回 null */
    public static Long parseUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
