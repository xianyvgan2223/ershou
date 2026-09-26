package com.ershou.campus.module.order.service;

import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面交单状态留痕（独立事务）。
 *
 * <p>典型场景：卖家接受约面交时商品已被别人锁定 → 必须把这张单子置为"已失效"，
 * 同时又要给调用方抛 30004。如果写在 {@code OrderService.accept} 的同一个事务里，
 * 异常会把"置失效"一起回滚，单子继续挂在"待卖家确认"，卖家点了没反应只能反复点。
 * 与私聊的 {@link com.ershou.campus.module.chat.service.ChatRiskRecorder} 同一套路：
 * 留痕不随主流程回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStateRecorder {

    private final MeetupOrderMapper orderMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void invalidate(Long orderId, Long operatorId, String reason) {
        MeetupOrder update = new MeetupOrder();
        update.setId(orderId);
        update.setStatus(5);
        update.setCancelBy(operatorId);
        update.setCancelReason(reason);
        orderMapper.updateById(update);
        log.info("[order][invalid][{}] 面交单置为已失效：{}", orderId, reason);
    }
}
