package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.ItemClient;
import com.ershou.cloud.admin.client.MapClient;
import com.ershou.cloud.admin.client.OrderClient;
import com.ershou.cloud.admin.client.UserClient;
import com.ershou.cloud.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 审核工作台概览（BFF 聚合）。
 *
 * <p>旧单体 dashboard 是 14 个 count(*) 直接查库；拆分后不允许跨库，改为汇总各服务 /inner/stats，
 * 并且 **每个数据源单独降级**：任何下游不可用时该指标返回 0，dashboard 本身永远 200
 * （管理端首页不能因为某个服务抖动整页打不开）。
 *
 * <p>键名与旧单体完全一致（前端 AdminDashboard 直接读）；新增 pendingReports 为附加字段，旧前端忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final ItemClient itemClient;
    private final UserClient userClient;
    private final MapClient mapClient;
    private final OrderClient orderClient;
    private final AdminLogService logService;
    private final AdminReportService reportService;

    public Map<String, Object> dashboard() {
        Map<String, Object> itemStats = safeMap(() -> itemClient.itemStats());
        Map<String, Object> userStats = safeMap(() -> userClient.userStats());
        Map<String, Object> pointStats = safeMap(() -> mapClient.pointStats());
        Map<String, Object> orderStats = safeMap(() -> orderClient.orderStats());

        Map<String, Object> data = new LinkedHashMap<>();
        // 商品域（item-service /inner/items/stats：{onSale,pendingReview,offline}）
        data.put("pendingItems", longOf(itemStats, "pendingReview"));
        data.put("onSaleItems", longOf(itemStats, "onSale"));
        data.put("rejectedItems", longOf(itemStats, "rejected"));
        data.put("totalItems", longOf(itemStats, "total"));
        // 点位域（map-service /inner/points/stats：{total,pending,disabled}）
        data.put("pendingPoints", longOf(pointStats, "pending"));
        data.put("disabledPoints", longOf(pointStats, "disabled"));
        // 用户域（user-service /inner/users/stats：{total,banned,newToday}）
        data.put("totalUsers", longOf(userStats, "total"));
        data.put("bannedUsers", longOf(userStats, "banned"));
        // 订单域（order-service /inner/orders/stats：{total,today,completed}）
        data.put("totalOrders", longOf(orderStats, "total"));
        data.put("todayOrders", longOf(orderStats, "today"));
        data.put("doneOrders", longOf(orderStats, "completed"));
        data.put("activeOrders", longOf(orderStats, "active"));
        data.put("riskOrders", longOf(orderStats, "risk"));
        // 本服务自己的域：今日操作数（审计表）+ 待受理举报（T107 新增）
        data.put("todayOperations", logService.todayOperations());
        data.put("pendingReports", reportService.pendingCount());
        return data;
    }

    private Map<String, Object> safeMap(Supplier<Result<Map<String, Object>>> supplier) {
        try {
            Map<String, Object> map = FeignResults.data(supplier.get(), "下游服务");
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            log.debug("[admin][overview] 指标降级为 0：{}", e.getMessage());
            return Map.of();
        }
    }

    private static long longOf(Map<String, Object> map, String key) {
        Long v = MapHelper.lng(map, key);
        return v == null ? 0L : v;
    }
}
