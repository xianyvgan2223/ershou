package com.ershou.cloud.order.service;

import com.ershou.cloud.order.config.RabbitConfig;
import com.ershou.cloud.order.event.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 订单事件 MQ 生产者（设计 4.4 / 5.2）。
 *
 * <p>用 {@link TransactionalEventListener} + {@code AFTER_COMMIT} 而不是在业务代码里直接
 * {@code convertAndSend}：只有本地事务（以及 Seata 全局事务）真正提交后才发消息，
 * {@code debugFail=true} 或任何异常回滚时<b>不会</b>发出"幽灵订单"事件，也不需要手写补偿。
 *
 * <p>消息体是 JSON <b>字符串</b>：{@code {orderId,orderNo,itemId,buyerId,quantity,createdAt}}
 * （消费方 item-service 按 {@code orderId} 做幂等并累加 {@code sale_count}）。
 * 发送失败只记日志，不回滚已提交订单 —— 演示场景允许丢弃事件，生产应加本地消息表/重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("orderId", event.orderId());
            payload.put("orderNo", event.orderNo());
            payload.put("itemId", event.itemId());
            payload.put("buyerId", event.buyerId());
            payload.put("quantity", event.quantity());
            payload.put("createdAt", event.createdAt());
            String json = objectMapper.writeValueAsString(payload);
            rabbitTemplate.convertAndSend(RabbitConfig.ORDER_EXCHANGE,
                    RabbitConfig.ORDER_CREATED_ROUTING_KEY, json);
            log.info("[order][mq] 已发送 order.created exchange={} rk={} body={}",
                    RabbitConfig.ORDER_EXCHANGE, RabbitConfig.ORDER_CREATED_ROUTING_KEY, json);
        } catch (Exception e) {
            // MQ 不是下单主链路：发送失败只告警，不影响已提交的订单
            log.error("[order][mq] 发送 order.created 失败（订单已提交，异步通知丢失）: {}", e.getMessage(), e);
        }
    }
}
