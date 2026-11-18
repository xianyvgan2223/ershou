package com.ershou.cloud.map.service;

import com.ershou.cloud.map.config.MapProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 空点位清理定时任务（从单体 {@code module/system/task/ScheduledTasks#cleanupEmptyTradePoints} 迁移）。
 *
 * <p>默认每天 03:10（cron 可在 Nacos {@code ershou.map.cleanup-cron} 调整；{@code cleanup-enabled=false} 可关闭）。
 * 规则与单体一致：点位上没有任何"还有戏"商品且创建超过宽限期（默认 7 天）才删；
 * item-service 不可用时整体跳过（见 {@link TradePointService#cleanupEmptyPoints()}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointCleanupTask {

    private final TradePointService tradePointService;
    private final MapProperties props;

    @Scheduled(cron = "${ershou.map.cleanup-cron:0 10 3 * * ?}")
    public void cleanupEmptyTradePoints() {
        if (!props.isCleanupEnabled()) {
            log.debug("[task][cleanup-point] 已关闭（ershou.map.cleanup-enabled=false），跳过");
            return;
        }
        try {
            int removed = tradePointService.cleanupEmptyPoints();
            if (removed > 0) {
                log.info("[task][cleanup-point] 清理 {} 个没有商品的点位", removed);
            }
        } catch (Exception e) {
            // 定时任务异常不能影响服务：记录后等下个周期
            log.error("[task][cleanup-point] 空点位清理失败：{}", e.getMessage(), e);
        }
    }
}
