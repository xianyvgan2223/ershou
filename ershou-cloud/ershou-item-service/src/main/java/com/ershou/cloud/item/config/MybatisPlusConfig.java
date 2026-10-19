package com.ershou.cloud.item.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件。
 *
 * <p><b>为什么必须有</b>：MyBatis-Plus 的 {@code selectPage} 依赖 {@link PaginationInnerInterceptor}
 * 才会拼 {@code LIMIT} 并回填 total。云服务是新建模块，没有单体里的 {@code MybatisPlusConfig}
 * — 漏了它实测表现是「pageSize=10 却返回全表 16 条、total=0」，前端分页器直接失效。
 *
 * <p><b>依赖注意</b>：MyBatis-Plus 3.5.9 把 {@code PaginationInnerInterceptor} 拆到了
 * {@code mybatis-plus-jsqlparser} 包（不在 extension 里），因此 item-service 的 pom 必须显式引
 * {@code com.baomidou:mybatis-plus-jsqlparser}（已由 platform-dev 加入，版本由父工程统一管理）。
 *
 * <p>{@code maxLimit=100} 作为 SQL 层兜底（业务层另有 Nacos 动态 {@code ershou.item.page-max} 上限）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}