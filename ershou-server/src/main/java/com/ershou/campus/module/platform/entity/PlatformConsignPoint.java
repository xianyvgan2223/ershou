package com.ershou.campus.module.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 平台代卖 / 自提点（管理员维护，设计 2.2）。
 *
 * <p>与 {@code trade_point}（校内地图面交点）是两套数据：
 * 代卖点是"官方自提货架"的所在地，由运营后台建与改；
 * item.platform_point_id 指向本表 id，<b>不是</b> trade_point.id。
 */
@Data
@TableName("platform_consign_point")
public class PlatformConsignPoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campusId;
    private String name;
    private String address;
    private BigDecimal lng;
    private BigDecimal lat;
    private String openHours;
    private String contactPhone;
    private String guideText;
    private String coverUrl;
    private Integer sort;
    /** 1=启用 0=停用（软删也置 0） */
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
