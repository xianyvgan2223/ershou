package com.ershou.cloud.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 订单业务参数（Nacos 配置动态刷新演示点，设计 4.6）。
 *
 * <p>对应 Nacos {@code ershou-order-service.yaml} 里的：
 * <pre>
 * ershou:
 *   order:
 *     max-qty: 99
 *     default-status: 2
 * </pre>
 * {@code @RefreshScope}：在启动的 Nacos 配置里把 {@code ershou.order.max-qty} 从 99 改成 1，
 * <b>不重启</b>服务，下一次下单请求即按新值校验（超过即返回 10001），用于演示配置中心动态生效。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "ershou.order")
public class OrderProperties {

    /** 单次下单数量上限（演示动态配置：Nacos 改动即时生效） */
    private int maxQty = 99;

    /** 云切片创建单的初始状态：2=待面交（设计 5.2） */
    private int defaultStatus = 2;
}
