package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运营台 · 平台订单行（GET /platform/admin/orders，只含 seller_id=平台账号）。
 * 字段与 Web `api/platform-console.ts#ConsoleAdminOrderVO` 对齐。
 */
@Data
public class AdminPlatformOrderVO {
    private Long id;
    private String orderNo;
    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private Long buyerId;
    /** 买家昵称（联 sys_user） */
    private String buyerNickname;
    private Integer quantity;
    /** 成交单价（列表展示 × quantity） */
    private BigDecimal dealPrice;
    /** 面交单状态：1 待卖家确认 / 2 待面交(自提) / 3 已完成 / 4 已取消 / 5 已失效 / 6 爽约 / 7 纠纷中 */
    private Integer status;
    private String statusText;
    /** 自提点 id（platform_consign_point.id） */
    private Long pointId;
    /** 自提点名称（联 platform_consign_point） */
    private String pointName;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
}
