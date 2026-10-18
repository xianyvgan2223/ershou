package com.ershou.campus.module.platform.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * item 表的「平台侧读写模型」（平台模块自有，不是 item 模块的 {@code Item} 实体）。
 *
 * <p>为什么不用 {@code com.ershou.campus.module.item.entity.Item}：
 * 平台代卖的读写 SQL 由平台模块自己的 {@code PlatformItemMapper} 维护（设计 3.1），
 * item 模块也在并行改动那个实体；这里只声明平台真正需要的列，
 * 既避免跨模块耦合，也不会因为对方加字段而互相等编译。
 *
 * <p>字段 {@code categoryName} / {@code pointName} 不落库，是列表 SQL join 出来的展示列。
 * 普通实体不会这样混用；这个类只作为 Mapper 的 SQL 返回/入参模型使用。
 */
@Data
public class PlatformItemRow {
    private Long id;
    private Long sellerId;
    private Long schoolId;
    private Long campusId;
    private String title;
    private String description;
    private Long categoryId;
    private Integer conditionLevel;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer negotiable;
    private Integer stock;
    private Integer isUnique;
    private String coverUrl;
    /** 图片 URL 数组的 JSON 字符串（DB 列是 JSON） */
    private String images;
    private Integer tradeMode;
    private Long preferPointId;
    /** 1=个人 2=平台（迁移脚本新增列） */
    private Integer sellerType;
    /** 平台代卖/自提点 id（platform_consign_point.id） */
    private Long platformPointId;
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;

    /* ---------- 查询 join 出来的展示字段（不写入 item 表） ---------- */
    private String categoryName;
    private String pointName;
}
