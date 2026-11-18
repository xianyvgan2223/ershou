package com.ershou.cloud.platform.runner;

import com.ershou.cloud.platform.service.PlatformCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 平台代卖缓存预热（设计第四节）：启动时把券/秒杀库存按 DB 真值写进 Redis。
 *
 * <p>为什么必须是 Runner 而不是 {@code @PostConstruct}：此时所有 Bean（含 Mapper / Redis）已就绪；
 * 预热失败也<b>不能</b>让应用启动失败 —— 抢券/抢购在未预热时返回 -1，
 * 运营可以调 {@code POST /api/v1/platform/admin/cache/refresh} 手动重建。
 *
 * <p>多实例部署时由 {@link PlatformCacheService#warmup(boolean)} 内部的
 * {@code SET NX EX 30} 分布式锁保证只有一个实例真正写 Redis。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformCacheWarmup implements ApplicationRunner {

    private final PlatformCacheService cacheService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int keys = cacheService.warmup(false);
            log.info("[platform][warmup] 启动预热完成，写入库存 key {} 个", keys);
        } catch (Exception e) {
            log.warn("[platform][warmup] 启动预热失败（不影响启动，可用后台刷新接口重试）：{}", e.getMessage());
        }
    }
}
