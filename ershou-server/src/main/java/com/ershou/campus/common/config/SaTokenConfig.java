package com.ershou.campus.common.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter.match("/api/**")
                        // 完全公开
                        .notMatch("/api/v1/auth/**", "/api/v1/categories", "/api/v1/schools/**",
                                  "/api/v1/map/**", "/api/v1/trade-points/**",
                                  // 搜索索引状态为只读诊断接口；重建索引 /api/v1/search/reindex 在 Controller 内校验管理员
                                  "/api/v1/search/status")
                        // 商品读接口公开；写接口（发布/下架）在 Controller 内自行校验登录
                        .notMatch("/api/v1/items", "/api/v1/items/*")
                        .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/**");
    }
}