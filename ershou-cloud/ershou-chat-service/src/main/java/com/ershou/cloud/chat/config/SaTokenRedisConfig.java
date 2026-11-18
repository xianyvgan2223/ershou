package com.ershou.cloud.chat.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoRedisJackson;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

/**
 * Sa-Token 会话专用 Redis 连接：**固定 db0**，与网关 8088 / 其它服务共享同一份登录态。
 *
 * <p>chat-service 的业务 Redis 落 **db4**（未读快照/在线心跳/跨实例广播），
 * 而 WS 握手必须用 db0 里的 Sa-Token 会话校验 —— 一个
 * {@code spring.data.redis.database} 无法同时满足，所以这里单独建一个指向 db0 的连接，
 * 手工初始化 {@link SaTokenDaoRedisJackson}（与单体/其它服务同库同版本，序列化格式一致）。
 *
 * <p>与 user-service 的 SaTokenRedisConfig 同款写法：连接工厂故意不注册成 Spring Bean，
 * 否则 Spring Boot 自动配置会退回，{@code StringRedisTemplate} 会被指到 db0，业务 key 写错库。
 */
@Slf4j
@Configuration
public class SaTokenRedisConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    /** 会话库：设计约定 = 0（唯一允许跨服务共用的 db） */
    @Value("${ershou.cloud.session-redis-database:0}")
    private int sessionDatabase;

    private LettuceConnectionFactory sessionFactory;

    @Bean
    @Primary
    public SaTokenDao saTokenDao() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
        factory.setDatabase(sessionDatabase);
        if (StringUtils.hasText(password)) {
            factory.setPassword(password);
        }
        factory.afterPropertiesSet();
        this.sessionFactory = factory;

        SaTokenDaoRedisJackson dao = new SaTokenDaoRedisJackson();
        dao.init(factory);
        log.info("[sa-token] 会话 Redis 指向 db{} ({}:{})：与网关 8088 / 其它服务共享登录态",
                sessionDatabase, host, port);
        return dao;
    }

    @PreDestroy
    public void destroy() {
        if (sessionFactory != null) {
            sessionFactory.destroy();
        }
    }
}
