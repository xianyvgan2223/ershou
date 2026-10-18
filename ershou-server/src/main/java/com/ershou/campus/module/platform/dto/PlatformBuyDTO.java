package com.ershou.campus.module.platform.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 平台商品直购入参（设计 3.3）。
 *
 * <p>couponId 本轮<b>不抵扣</b>，只记录日志（TODO(round3)），保留字段避免前端改接口。
 */
@Data
public class PlatformBuyDTO {

    @Min(value = 1, message = "购买数量至少 1")
    private Integer quantity = 1;

    /** 优惠券 id：本轮不抵扣，仅预留（设计 3.3 / 第八节） */
    private Long couponId;
}
