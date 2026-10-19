package com.ershou.cloud.common.config;

import com.ershou.cloud.common.exception.GlobalExceptionHandler;
import com.ershou.cloud.common.util.PhoneCrypto;
import com.ershou.cloud.common.web.TraceIdFilter;
import com.ershou.cloud.common.web.UserContextInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 公共包自动装配。
 *
 * <p>为什么用 AutoConfiguration 而不是让服务 {@code @ComponentScan("com.ershou.cloud")}：
 * 各服务 base package 是 {@code com.ershou.cloud.user/item/order}，扫不到同级的
 * {@code com.ershou.cloud.common}；自动装配让三个服务<b>零配置</b>获得统一返回/异常/TraceId/用户上下文。
 *
 * <p>Servlet 相关的 Bean 只在 Servlet Web 应用里注册（以后若 gateway 依赖 common 也不会冲突）。
 */
@AutoConfiguration
public class CloudCommonAutoConfiguration {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public PhoneCrypto phoneCrypto(@Value("${ershou.crypto.phone-key:__REDACTED_PHONE_KEY__}") String phoneKey) {
        return new PhoneCrypto(phoneKey);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public UserContextInterceptor userContextInterceptor() {
        return new UserContextInterceptor();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public WebMvcConfigurer cloudCommonWebMvcConfigurer(UserContextInterceptor userContextInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(userContextInterceptor).addPathPatterns("/**");
            }
        };
    }
}
