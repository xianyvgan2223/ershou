package com.ershou.cloud.user.config;

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
 * Sa-Token 会话专用 Redis 连接：**固定 db0**，与单体 8080 / 网关 8088 共享同一份登录态。
 *
 * <h3>为什么需要这个类（不能只靠 spring.data.redis.database）</h3>
 * user-service 的业务 Redis（短信验证码）按设计 2.2 必须落在 **db1**（`ershou:user:` 前缀），
 * 而 Sa-Token 会话必须落在 **db0** 才能与其他服务互通 —— 一个 `spring.data.redis.database`
 * 无法同时满足。所以这里单独建一个指向 db0 的 Lettuce 连接，并手工初始化
 * {@link SaTokenDaoRedisJackson}（与单体同库同版本，序列化格式一致），
 * 业务侧继续用 Spring Boot 自动配置的 db1 连接。
 *
 * <h3>注意</h3>
 * 这个连接工厂**故意不注册成 Spring Bean**（方法内局部创建）：一旦向容器暴露另一个
 * `RedisConnectionFactory`，Spring Boot 的自动配置会退回，`StringRedisTemplate` 就会被
 * 指向 db0，业务 key 反而写错库。连接生命周期由 {@link #destroy()} 收尾。
 */
@Slf4j
@Configuration
public class SaTokenRedisConfig {

    /** 复用业务 Redis 的 host/port/password（Nacos 里的 spring.data.redis.*），只把 db 换成 0 */
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

    /**
     * 会话 dao（db0）。
     *
     * <p>加 {@code @Primary} 的原因：`sa-token-redis-jackson` 的 {@code SaTokenDaoRedisJackson}
     * 自己在 `AutoConfiguration.imports` 里注册了一个 `@Component` 版 bean（它会用业务 Redis
     * 也就是 db1 去初始化）——两个 `SaTokenDao` 同时存在会让 Sa-Token 的注入报
     * "expected single matching bean but found 2"。这里显式声明本 bean 为主，Sa-Token 就用
     * 我们这条 db0 连接；自动配置那个 bean 不参与会话读写（保留不影响正确性，删它反而要动依赖）。
     */
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
        // 与单体 sa-token-redis-jackson 自动配置走同一个 init()，JSON 序列化格式一致
        dao.init(factory);
        log.info("[sa-token] 会话 Redis 指向 db{} ({}:{})：与单体 8080 / 网关 8088 共享登录态",
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
