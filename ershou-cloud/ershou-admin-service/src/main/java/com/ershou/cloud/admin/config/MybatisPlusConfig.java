package com.ershou.cloud.admin.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件：分页拦截器（MP 3.5.9 起 PaginationInnerInterceptor 在独立模块
 * {@code mybatis-plus-jsqlparser}，pom 已引入）。
 *
 * <p>没有这个拦截器时 {@code selectPage} 不会拼 LIMIT、也不会回填 total
 * （本服务首轮冒烟就踩到：审计日志 pageSize=3 却返回 893 条、total=0）。
 * 分页上限由各 Service 自己 clamp（MAX_PAGE_SIZE=100），这里只负责把分页下推到 SQL。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数兜底：即使某处忘了 clamp，也不会一次捞出整张表
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
