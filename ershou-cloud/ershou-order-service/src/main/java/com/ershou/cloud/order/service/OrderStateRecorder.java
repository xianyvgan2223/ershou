package com.ershou.cloud.order.service;

import com.ershou.cloud.order.entity.MeetupOrder;
import com.ershou.cloud.order.mapper.MeetupOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面交单"置失效"的独立事务记录器（从单体 {@code OrderStateRecorder} 迁入）。
 *
 * <p>典型场景：卖家接受时商品已被别人锁走 → 必须把本单置为已失效，同时又要给调用方抛 30004；
 * 若写在同一个事务里，异常会把"置失效"一起回滚，卖家点接受没反应、只能反复点。
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
