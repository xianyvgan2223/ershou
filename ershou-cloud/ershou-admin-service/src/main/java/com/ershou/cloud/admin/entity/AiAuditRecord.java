package com.ershou.cloud.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 审核记录（ershou_admin.ai_audit_record）。
 *
 * <p>归属约定（full-split-contract 第六节）：表归 admin 库，item-service 不建/不写。
 * 旧单体机审结果只写 item.audit_result/日志、从未落这张表（历史遗留），所以当前数据为空；
 * 本服务提供只读管理接口 {@code GET /api/v1/admin/ai-audit-records}，后续机审落库可直接写入本表。
 */
@Data
@TableName("ai_audit_record")
public class AiAuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** item/item_image/chat/report/shop_license */
    private String bizType;
    private Long bizId;
    private String modelVersion;
    /** 命中标签及分数（JSON 字符串） */
    private String labels;
    /** 最高置信度 */
    private BigDecimal score;
    /** 1=放行 2=拦截 3=转人工 */
    private Integer decision;
    private String reason;
    /** 人审操作人 */
    private Long operatorId;
    /** 0=无 1=申诉中 2=维持 3=改判 */
    private Integer appealStatus;
    private Integer costMs;
    private LocalDateTime createdAt;
}
