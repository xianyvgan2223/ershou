package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.CacheProperties;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.admin.entity.SysAuditLog;
import com.ershou.campus.module.admin.mapper.SysAuditLogMapper;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 审核工作台概览：待办与总量统计 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private static final TypeReference<Map<String, Object>> DASHBOARD = new TypeReference<>() {
    };

    private final ItemMapper itemMapper;
    private final TradePointMapper pointMapper;
    private final MeetupOrderMapper orderMapper;
    private final SysUserMapper userMapper;
    private final SysAuditLogMapper auditLogMapper;
    private final JsonCache cache;
    private final CacheProperties cacheProps;

    /**
     * 审核工作台概览：14 个 count(*)（含三张全表 count），本来每次打开后台都要打一遍 DB。
     * 统计允许秒级延迟，套 30 秒读缓存；运营做完处置后由 {@code AuditLogService.record} 主动删缓存。
     */
    public Map<String, Object> dashboard() {
        return cache.getOrLoad(RedisKeys.adminDashboard(), cacheProps.getDashboardTtl(), DASHBOARD, this::countAll);
    }

    private Map<String, Object> countAll() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pendingItems", itemMapper.selectCount(Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 1)));
        data.put("pendingPoints", pointMapper.selectCount(
                Wrappers.<TradePoint>lambdaQuery().eq(TradePoint::getAuditStatus, 0)));
        data.put("onSaleItems", itemMapper.selectCount(Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 2)));
        data.put("rejectedItems", itemMapper.selectCount(Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 6)));
        data.put("totalItems", itemMapper.selectCount(null));
        data.put("totalUsers", userMapper.selectCount(null));
        data.put("bannedUsers", userMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getStatus, 3)));
        data.put("todayOperations", auditLogMapper.selectCount(
                Wrappers.<SysAuditLog>lambdaQuery().ge(SysAuditLog::getCreatedAt, todayStart)));
        // 面交单口径：1 待卖家确认 / 2 待面交 / 3 已完成 / 6 爽约 / 7 纠纷中
        data.put("totalOrders", orderMapper.selectCount(null));
        data.put("activeOrders", orderMapper.selectCount(
                Wrappers.<MeetupOrder>lambdaQuery().in(MeetupOrder::getStatus, 1, 2)));
        data.put("doneOrders", orderMapper.selectCount(
                Wrappers.<MeetupOrder>lambdaQuery().eq(MeetupOrder::getStatus, 3)));
        data.put("riskOrders", orderMapper.selectCount(
                Wrappers.<MeetupOrder>lambdaQuery().in(MeetupOrder::getStatus, 6, 7)));
        data.put("todayOrders", orderMapper.selectCount(
                Wrappers.<MeetupOrder>lambdaQuery().ge(MeetupOrder::getCreatedAt, todayStart)));
        // 停用（下架）的点位：status=0，不再出现在地图上
        data.put("disabledPoints", pointMapper.selectCount(
                Wrappers.<TradePoint>lambdaQuery().eq(TradePoint::getStatus, 0)));
        return data;
    }
}
