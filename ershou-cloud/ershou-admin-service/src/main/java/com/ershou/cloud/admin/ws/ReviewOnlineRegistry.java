package com.ershou.cloud.admin.ws;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 审核员在线数（本机 WS 连接计数）。
 *
 * <p>首版实时推送只做握手/ping-pong（广播事件由 item-service 侧产生，见遗留），
 * 但"几位审核员在线"这个统计仍有意义，且与旧单体 ItemReviewStatsVO.onlineReviewers 字段对齐。
 * 多实例部署时应改读 Redis（旧单体用 ershou:admin:review:online Hash），当前演示单实例。
 */
@Component
public class ReviewOnlineRegistry {

    private final AtomicInteger online = new AtomicInteger(0);

    public void increment() {
        online.incrementAndGet();
    }

    public void decrement() {
        online.updateAndGet(v -> Math.max(0, v - 1));
    }

    public int count() {
        return online.get();
    }
}
