package com.ershou.cloud.item.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 声明与消费容器配置（设计 5.2）。
 *
 * <p>交换/队列/绑定与 order-service 生产者完全一致，两边重复声明是幂等的（谁先启动谁建）：
 * <ul>
 *   <li>Exchange：{@code ershou.order.topic}（topic, durable）</li>
 *   <li>Queue：{@code ershou.item.order-created.queue}（durable，死信进 {@code ershou.order.dlx}）</li>
 *   <li>Binding：{@code order.created}</li>
 * </ul>
 *
 * <p><b>手动 ack 不依赖 Nacos 配置</b>：这里单独声明 {@code itemListenerContainerFactory}（MANUAL），
 * 保证即使 Nacos 里漏配 {@code spring.rabbitmq.listener.simple.acknowledge-mode} 也按演示要求手动 ack。
 */
@Configuration
public class RabbitConfig {

    /** 订单事件交换机（与 order-service 生产者一致） */
    public static final String ORDER_EXCHANGE = "ershou.order.topic";
    /** 下单成功路由键 */
    public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
    /** item-service 消费队列 */
    public static final String ORDER_CREATED_QUEUE = "ershou.item.order-created.queue";
    /** 死信交换机 + 死信队列（消费失败 nack 不重回队列，进这里便于排查） */
    public static final String ORDER_DLX = "ershou.order.dlx";
    public static final String ORDER_CREATED_DLQ = "ershou.item.order-created.dlq";
    /** 手动 ack 专用监听容器工厂名 */
    public static final String LISTENER_FACTORY = "itemListenerContainerFactory";

    @Bean
    public TopicExchange orderTopicExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderDeadLetterExchange() {
        return new DirectExchange(ORDER_DLX, true, false);
    }

    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable(ORDER_CREATED_QUEUE)
                .deadLetterExchange(ORDER_DLX)
                .deadLetterRoutingKey(ORDER_CREATED_DLQ)
                .build();
    }

    @Bean
    public Queue orderCreatedDeadLetterQueue() {
        return QueueBuilder.durable(ORDER_CREATED_DLQ).build();
    }

    @Bean
    public Binding orderCreatedBinding() {
        return BindingBuilder.bind(orderCreatedQueue())
                .to(orderTopicExchange())
                .with(ORDER_CREATED_ROUTING_KEY);
    }

    @Bean
    public Binding orderCreatedDeadLetterBinding() {
        return BindingBuilder.bind(orderCreatedDeadLetterQueue())
                .to(orderDeadLetterExchange())
                .with(ORDER_CREATED_DLQ);
    }

    /**
     * 手动 ack 的监听容器工厂：消费方法自己 channel.basicAck / basicNack。
     * 失败 nack(requeue=false) → 按队列参数进死信，不会无限重投。
     */
    @Bean(name = LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory itemListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(10);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        // 消费端自己解析 JSON 字节，避免 producer 侧 converter 差异导致 MessageConversionException
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}
