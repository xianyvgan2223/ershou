package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** AI 审核记录视图（ershou_admin.ai_audit_record，T107 新增只读接口） */
@Data
public class AiAuditRecordVO {
    private Long id;
    /** item/item_image/chat/report/shop_license */
    private String bizType;
    private Long bizId;
    private String modelVersion;
    /** 命中标签（JSON 字符串原样返回，前端可 JSON.parse） */
    private String labels;
    private BigDecimal score;
    /** 1=放行 2=拦截 3=转人工 */
    private Integer decision;
    private String decisionText;
    private String reason;
    private Long operatorId;
    private Integer appealStatus;
    private String appealStatusText;
    private Integer costMs;
    private LocalDateTime createdAt;
}
