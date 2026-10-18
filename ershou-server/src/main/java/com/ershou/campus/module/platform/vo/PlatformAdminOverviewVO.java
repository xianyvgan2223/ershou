package com.ershou.campus.module.platform.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 运营台 · 概览统计（GET /platform/admin/overview）。
 * 字段与 Web `api/platform-console.ts#ConsoleOverviewVO` 对齐，口径见设计 3.1。
 */
@Data
public class PlatformAdminOverviewVO {
    /** 在售平台商品数 */
    private Long platformItemOnSale;
    /** 平台商品总数（含已下架/售出等全部状态） */
    private Long platformItemTotal;
    /** 启用中的代卖点数 */
    private Long pointCount;
    /** 券模板数量 */
    private Long couponCount;
    /** 券总库存（SUM total_stock） */
    private Long couponTotalStock;
    /** 券已领取总量 */
    private Long couponClaimed;
    /** 进行中出现/最近场次 id；无场次为 null */
    private Long flashSceneId;
    private String flashSceneTitle;
    private Integer flashSceneStatus;
    /** 场次秒杀剩余库存（汇总） */
    private Integer flashStockLeft;
    private Integer flashStockTotal;
    private Integer flashSold;
    /** 今日平台订单数（seller_id=平台账号 且 created_at >= 今天 0 点） */
    private Long orderToday;
    private Long orderTotal;
    /** GMV = SUM(deal_price * quantity) */
    private BigDecimal gmvToday;
    private BigDecimal gmvTotal;
    /** 待自提（status=2） */
    private Long pendingPickup;
    /** 待确认（status=1） */
    private Long waitingConfirm;
    /** 在售平台商品中 stock<=2 的数量 */
    private Long lowStockItems;
    /** 进行中场次距结束小时数；无场次为 null */
    private Long expiringFlashHours;
}
