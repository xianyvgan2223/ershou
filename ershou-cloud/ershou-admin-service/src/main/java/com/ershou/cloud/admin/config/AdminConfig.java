package com.ershou.cloud.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 运营后台配置绑定（AdminProperties/ReviewProperties 由 Nacos 下发） */
@Configuration
@EnableConfigurationProperties({AdminProperties.class, ReviewProperties.class})
public class AdminConfig {
}
