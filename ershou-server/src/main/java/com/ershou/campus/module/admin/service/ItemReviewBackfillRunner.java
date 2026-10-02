package com.ershou.campus.module.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时补登记：把"在售却没有上架审核记录"的商品补进「上架审核」页。
 *
 * <p>为什么必须有这一步：这个页面的价值在于<b>完整性</b>——"每条商品都被看过"。
 * 只要有一条在售商品因为入队异常（异常被吞）、或上线前就已存在而没进队列，
 * 这个保证就破了，而且不会有任何报错提醒。
 *
 * <p>补登记按<b>待审</b>入册（真的没人看过）。为了避免上线瞬间把几百条历史商品
 * 砸成待审，{@code migration-item-review.sql} 已经给存量数据建了"已审核"档案，
 * 所以这里通常只捞到极少数真正的漏网之鱼。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class ItemReviewBackfillRunner implements ApplicationRunner {

    /** 一次最多补多少条，避免启动被拖慢（要更多可在审核页点「补登记」按钮） */
    private static final int LIMIT = 500;

    private final ItemReviewService reviewService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int added = reviewService.backfillMissing(LIMIT);
            long pending = reviewService.pendingCount();
            log.info("[review][startup] 上架审核补登记完成：新增 {} 条，当前待审核 {} 条", added, pending);
        } catch (Exception e) {
            // 补登记失败不能挡住应用启动（商品发布/浏览都不依赖它）
            log.warn("[review][startup] 上架审核补登记失败（不影响启动）：{}", e.getMessage());
        }
    }
}
