package com.ershou.cloud.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用分流水 VO：用户「信用分记录」列表 / admin 用户详情用。
 * 字段与旧单体 {@code credit_log} 表一致。
 */
@Data
public class CreditLogVO {

    private Long id;

    private Long userId;

    /** +2 / -2 等 */
    private Integer changeValue;

    private Integer beforeScore;

    private Integer afterScore;

    private String reason;

    /** order=面交完成自动加减分；admin=运营人工调整 */
    private String bizType;

    private Long bizId;

    private Long operatorId;

    private LocalDateTime createdAt;
}
