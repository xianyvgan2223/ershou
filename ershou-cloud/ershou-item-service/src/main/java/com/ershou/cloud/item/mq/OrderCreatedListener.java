package com.ershou.cloud.item.mq;

import com.ershou.cloud.item.config.RabbitConfig;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.support.RedisKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * RabbitMQ 消费者：{@code order.created} → 异步累加 {@code item.sale_count}（设计 5.2）。
 *
 * <p>消息体（order-service 生产者约定）：{@code {orderId,orderNo,itemId,buyerId,quantity,createdAt}}（JSON）。
 *
 * <p>可靠性 / 幂等：
 * <ul>
 *   <li><b>手动 ack</b>：容器工厂 {@link RabbitConfig#LISTENER_FACTORY} 显式 MANUAL；成功 basicAck，
 *       失败 basicNack(requeue=false) → 队列参数把消息投进死信 {@code ershou.order.dlx}，不会无限重投；</li>
 *   <li><b>幂等</b>：Redis db2 {@code ershou:item:mq:order:{orderId}} SETNX EX 24h，重复投递直接 ack 跳过；
 *       若首次处理失败会先删掉幂等 key 再 nack，保证重投时仍可重试；</li>
 *   <li><b>ack 时机</b>：DB 更新 + 缓存失效都成功后才 ack。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreatedListener {

    private final ItemMapper itemMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    @RabbitListener(queues = RabbitConfig.ORDER_CREATED_QUEUE, containerFactory = RabbitConfig.LISTENER_FACTORY)
    public void onOrderCreated(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Long orderId = null;
        String idemKey = null;
        try {
            JsonNode node = json.readTree(body);
            orderId = node.path("orderId").asLong();
            long itemId = node.path("itemId").asLong();
            int quantity = Math.max(node.path("quantity").asInt(1), 1);
            String buyerId = node.hasNonNull("buyerId") ? node.path("buyerId").asText() : "?";

            idemKey = RedisKeys.mqOrder(orderId);
            Boolean first = redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofHours(24));
            if (!Boolean.TRUE.equals(first)) {
                log.info("[mq][order-created][dup] orderId={} 已消费过，跳过并 ack", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }

            int rows = itemMapper.incrSaleCount(itemId, quantity);
            // 销量变化与库存变化都会影响详情展示，清掉 db2 详情缓存
            redis.delete(RedisKeys.itemDetail(itemId));
            if (rows == 0) {
                log.warn("[mq][order-created] orderId={} itemId={} 商品不存在，sale_count 未累加（照常 ack 防毒消息）",
                        orderId, itemId);
            } else {
                log.info("[mq][order-created] orderId={} itemId={} buyerId={} qty={} → item.sale_count += {}（已清详情缓存）",
                        orderId, itemId, buyerId, quantity, quantity);
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 首次处理失败：释放幂等 key，让消息重投（或进死信后人工重放）时能再处理
            releaseIdemKey(idemKey);
            log.warn("[mq][order-created] 消费失败 orderId={} body={} err={} → nack(requeue=false) 进死信",
                    orderId, body, e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void releaseIdemKey(String idemKey) {
        if (idemKey == null) {
            return;
        }
        try {
            redis.delete(idemKey);
        } catch (Exception e) {
            log.warn("[mq][order-created] 释放幂等 key 失败（不影响重投）：{}", e.getMessage());
        }
    }
}
