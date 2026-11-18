package com.ershou.cloud.order.dto;

import lombok.Data;

/**
 * 内部信用分流水写入请求（供 admin-service 人工调整后留痕时调用）。
 * admin-service 走 user-service 改分数，再调本服务 {@code POST /inner/credit-logs} 记流水。
 */
@Data
public class CreditLogRecordDTO {

    private Long userId;

    private Integer changeValue;

    private Integer beforeScore;

    private Integer afterScore;

    private String reason;

    /** admin=运营人工调整；order=面交完成（内部自动写，不走本接口） */
    private String bizType = "admin";

    private Long bizId;

    private Long operatorId;
}
