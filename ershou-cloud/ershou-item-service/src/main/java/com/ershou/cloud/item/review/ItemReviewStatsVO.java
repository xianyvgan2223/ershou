package com.ershou.cloud.item.review;

import lombok.Data;

import java.time.LocalDateTime;

/** 上架审核页的顶部统计 + 实时连接信息 */
@Data
public class ItemReviewStatsVO {

    /** 待审核条数（审核页的主数字：还有多少条没看过） */
    private long pending;
    /** 今天已处置条数 */
    private long reviewedToday;
    /** 今天新上架（含改名重新入队）条数 */
    private long queuedToday;
    /** 当前在售商品总数（分母：今天该看的量级） */
    private long onSaleTotal;
    /** 被处置过但其实已不在待审范围的条数（已售出/已下架等，只读留痕） */
    private long stalePending;
    /** 当前有多少审核员开着这个页面（本机连接 + Redis 在线名单） */
    private int onlineReviewers;
    private LocalDateTime serverTime;
}
