package com.ershou.campus.module.map.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trade_point")
public class TradePoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campusId;
    private String name;
    private Integer source;
    private Integer pointType;
    private BigDecimal coordX;
    private BigDecimal coordY;
    private BigDecimal lng;
    private BigDecimal lat;
    private String availablePeriod;
    private String safeTags;
    private Integer safetyScore;
    private String guideText;
    private Integer status;
    private Integer auditStatus;
    private Long mergeGroup;
    private Integer dealCount;
    private Integer disputeCount;
    private Long creatorId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
