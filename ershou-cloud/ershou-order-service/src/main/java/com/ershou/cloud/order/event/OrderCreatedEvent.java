package com.ershou.cloud.order.event;

/**
 * 下单成功事件（进程内 Spring 事件，不是 MQ 消息本身）。
 *
 * <p>由 {@code MeetupOrderService} 在事务内 publish，{@code OrderEventPublisher} 用
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 监听：只有事务真正提交后才发 MQ，
 * 回滚（含 Seata 全局回滚）时不会发出"幽灵订单"事件。
 *
 * @param orderId   面交单 id
 * @param orderNo   面交单号
 * @param itemId    商品 id
 * @param buyerId   买家 id
 * @param quantity  数量
 * @param createdAt 创建时间（yyyy-MM-dd HH:mm:ss，MQ 消息里用字符串避免时区/格式歧义）
 */
public record OrderCreatedEvent(Long orderId,
                                String orderNo,
                                Long itemId,
                                Long buyerId,
                                Integer quantity,
                                String createdAt) {
}
