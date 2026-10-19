package com.ershou.cloud.order.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页插件。
 *
 * <p>MyBatis-Plus 3.5.9 起分页插件依赖 `mybatis-plus-jsqlparser`（pom 由 platform-dev 维护）。
 * 不注册 {@link PaginationInnerInterceptor} 时 {@code selectPage(...)} 不会真正分页、
 * 且 {@code Page.getTotal()} 恒为 0（实测：/mine 返回 records 有数据但 total=0）——
 * 注册后 {@code GET /api/v1/meetup-orders/mine} 的 total/pageNum/pageSize 才与前端分页组件语义一致。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
