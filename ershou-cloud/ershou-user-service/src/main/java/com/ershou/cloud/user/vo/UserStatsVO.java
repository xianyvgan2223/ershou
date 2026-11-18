package com.ershou.cloud.user.vo;

import lombok.Data;

/**
 * 用户统计（`GET /inner/users/stats`，admin-service 概览卡片用）。
 */
@Data
public class UserStatsVO {

    /** 用户总数 */
    private long total;

    /** 封禁用户数（status=3） */
    private long banned;

    /** 今日新增（created_at >= 今天 00:00） */
    private long newToday;
}
