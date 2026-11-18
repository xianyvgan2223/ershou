package com.ershou.cloud.user.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件（T101 新增）。
 *
 * <p><b>为什么必须有</b>：`selectPage` 依赖 {@link PaginationInnerInterceptor} 才会拼 `LIMIT`
 * 并回填 total。user-service 原切片没有分页查询，所以一直没注册这个插件；
 * T101 迁入管理端用户列表 `GET /inner/users` 后必须有，否则 total 恒为 0、返回全表。
 *
 * <p><b>依赖注意</b>：MyBatis-Plus 3.5.9 把该插件拆到 `mybatis-plus-jsqlparser` 包，
 * 本服务 pom 已显式引入（版本由父工程统一管理）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // SQL 层兜底：业务层（UserService.MAX_PAGE_SIZE）另有 100 上限
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
