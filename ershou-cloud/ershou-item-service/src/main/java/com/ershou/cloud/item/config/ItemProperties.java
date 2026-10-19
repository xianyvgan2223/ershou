package com.ershou.cloud.item.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * item-service 业务参数（Nacos 动态配置演示点之一，设计 4.6）。
 *
 * <p>放在 Nacos 的 {@code ershou-item-service.yaml} 里，例如把 {@code ershou.item.page-max} 从 100 改成 20，
 * 不重启服务，下一次请求即生效（{@code @RefreshScope} 重新绑定）。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "ershou.item")
public class ItemProperties {

    /** 详情缓存秒数（默认 60s，与设计 4.3 一致） */
    private int detailCacheSeconds = 60;

    /** 列表分页大小上限（默认 100；Nacos 可动态调小做演示） */
    private int pageMax = 100;
}
