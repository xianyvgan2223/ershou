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
                                  // 平台严选公开读（第 2 轮平台代卖）：游客也能看专区/代卖点/券/秒杀
                                  // 领券/抢购/直购（/platform/me/**）与后台（/platform/admin/**）仍需登录
                                  "/api/v1/platform/shop/**",
                                  // 搜索索引状态为只读诊断接口；重建索引 /api/v1/search/reindex 在 Controller 内校验管理员
                                  "/api/v1/search/status")
                        // 商品读接口公开；写接口（发布/下架）在 Controller 内自行校验登录
                        .notMatch("/api/v1/items", "/api/v1/items/*")
                        // 用户主页公开（不含任何联系方式，见 UserService.publicProfile）
                        .notMatch("/api/v1/users/*/profile")
                        .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/**");
    }
}