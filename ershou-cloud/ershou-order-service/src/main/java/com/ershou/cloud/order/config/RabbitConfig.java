package com.ershou.cloud.order.config;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 生产者配置（设计 5.2）。
 *
 * <p>order-service 只负责声明并往 <b>exchange</b> 发消息；队列 {@code ershou.item.order-created.queue}
 * 与 binding、死信参数由消费方 item-service 声明（谁消费谁声明队列，避免两端声明参数不一致报
 * {@code PRECONDITION_FAILED}）。
 *
 * <ul>
 *   <li>Exchange：{@code ershou.order.topic}（topic，durable）</li>
 *   <li>Routing key：{@code order.created}</li>
 *   <li>消息体：JSON <b>字符串</b>（设计 5.2），
 *       {@code {orderId,orderNo,itemId,buyerId,quantity,createdAt}}</li>
 * </ul>
 */
@Configuration
public class RabbitConfig {

    /** 订单事件 topic exchange */
    public static final String ORDER_EXCHANGE = "ershou.order.topic";

    /** 下单事件 routing key */
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";

    @Bean
    public TopicExchange orderTopicExchange() {
        return ExchangeBuilder.topicExchange(ORDER_EXCHANGE)
                .durable(true)
                .build();
    }
}
