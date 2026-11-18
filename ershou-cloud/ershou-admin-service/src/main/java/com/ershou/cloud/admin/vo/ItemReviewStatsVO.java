package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 上架审核统计（字段与旧单体 ItemReviewStatsVO 完全一致） */
@Data
public class ItemReviewStatsVO {
    private long pending;
    private long reviewedToday;
    private long queuedToday;
    private long onSaleTotal;
    private long stalePending;
    private int onlineReviewers;
    private LocalDateTime serverTime;
}
