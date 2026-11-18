package com.ershou.cloud.map.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部接口（{@code /inner/points/**}）用的点位视图。
 *
 * <p>契约（full-split-contract.md 第三/六节）最小字段：{@code id,name,guide,longitude,latitude,campusId}。
 * 管理端（admin-service）还需要状态/审核/安全分等，这里做成**超集**：字段只增不减，对最小契约调用方无影响。
 * 空值不下发（{@link JsonInclude.Include#NON_NULL}），避免把 null 字段塞给消费方。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InnerPointVO {
    private Long id;
    private String name;
    /** 到点指引（契约字段名 guide，对应 trade_point.guide_text） */
    private String guide;
    /** 经度（GCJ-02，契约字段名 longitude，对应 trade_point.lng） */
    private BigDecimal longitude;
    /** 纬度（GCJ-02，契约字段名 latitude，对应 trade_point.lat） */
    private BigDecimal latitude;
    private Long campusId;

    /** 以下为管理端超集字段 */
    private Integer type;
    /** 1=启用 0=停用 */
    private Integer status;
    /** 0=待审 1=通过 2=驳回 */
    private Integer auditStatus;
    private Integer source;
    private Integer safetyScore;
    private List<String> safeTags;
    private BigDecimal coordX;
    private BigDecimal coordY;
    /** 点位商品数（"还有戏"：草稿/审核中/在售/已锁定），来自 item-service */
    private Integer itemCount;
    /** 买家可见在售数（status=2），来自 item-service */
    private Integer onSaleCount;
    private BigDecimal minPrice;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
