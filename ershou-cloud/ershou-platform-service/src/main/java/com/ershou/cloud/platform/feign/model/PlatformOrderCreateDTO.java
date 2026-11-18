package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建平台单入参（platform-service → order-service /inner/orders/platform）。
 *
 * <p>平台单语义：卖家固定为平台官方账号；dealPrice 由平台侧按「商品价/秒杀价」服务端计算后传入，
 * 买家无法控制；pointId 是 platform_consign_point.id（不是 trade_point.id）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlatformOrderCreateDTO {

    private Long itemId;
    private Long buyerId;
    private Integer quantity;
    private BigDecimal dealPrice;
    private Long pointId;

    /** true=平台款（order-service InnerCreateOrderDTO 语义；dealPrice 非空时其实也会走平台款） */
    private Boolean platformOrder = true;
}
