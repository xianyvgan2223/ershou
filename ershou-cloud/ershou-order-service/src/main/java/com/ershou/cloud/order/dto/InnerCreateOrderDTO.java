package com.ershou.cloud.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 内部建单请求（供 admin/platform 等服务内部调用，不属于前端契约）。
 *
 * <p>模式：
 * <ul>
 *   <li>个人款（默认）：生成 status=1 待卖家确认的意向单（同 {@code POST /api/v1/meetup-orders}），
 *       buyerId 必填；</li>
 *   <li>平台款：{@code platformOrder=true} 或 {@code dealPrice != null} 时走"平台代卖自提单"——
 *       调 item-service 条件扣库存、订单直接 status=2、seller_id=平台账号(默认 1000)、
 *       seller_confirm_at=now，供 platform-service 调用。</li>
 * </ul>
 */
@Data
public class InnerCreateOrderDTO {

    private Long buyerId;

    private Long itemId;

    private Integer quantity = 1;

    private String remark;

    /** 平台款成交价（秒杀价/商品价，平台侧计算后传入；为空则用 item 现价） */
    private BigDecimal dealPrice;

    /** 平台代卖/自提点 id（platform_consign_point.id） */
    private Long pointId;

    /** true=平台款（直接扣库存+status=2），false=个人意向单 */
    private Boolean platformOrder = false;

    /** 平台款卖家 id（默认取 Nacos ershou.order.platform-seller-id=1000） */
    private Long sellerId;
}
