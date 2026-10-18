package com.ershou.campus.module.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营台 · 平台商品行（GET /platform/admin/items，含全部状态）。
 * 字段与 Web `api/platform-console.ts#ConsoleAdminItemVO` 对齐。
 */
@Data
public class AdminPlatformItemVO {
    private Long id;
    private String title;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String coverUrl;
    private Integer stock;
    /** 1 审核中 / 2 在售 / 3 已锁定 / 4 已售出 / 5 已下架 / 7 已过期 … */
    private Integer status;
    private String statusText;
    /** 平台代卖点（platform_consign_point.id，不是 trade_point.id） */
    private Long pointId;
    private String pointName;
    /** 固定 2（平台商品） */
    private Integer sellerType;
    private Integer viewCount;
    private LocalDateTime publishedAt;
    private LocalDateTime expireAt;
}
