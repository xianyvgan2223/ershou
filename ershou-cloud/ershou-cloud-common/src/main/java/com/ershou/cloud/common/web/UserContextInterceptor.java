package com.ershou.cloud.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把网关注入的 {@code X-User-Id} 放进 {@link UserContext}，请求结束清理。
 * 由 {@code CloudCommonAutoConfiguration} 注册到所有 Servlet 服务。
 */
public class UserContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.setUserId(ApiHeaderSupport.resolveUserId(request));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
