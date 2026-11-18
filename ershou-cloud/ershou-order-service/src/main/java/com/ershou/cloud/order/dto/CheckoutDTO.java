package com.ershou.cloud.order.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 购物车合并结算请求（与旧单体 {@code module.order.dto.CheckoutDTO} JSON 完全兼容）。
 *
 * <p>pointId/timeSlot* 为历史字段：旧单体服务端已忽略（买家只提意向，点位按每件商品的 preferPointId、
 * 时间由卖家定），这里保留字段但不读，保证老前端/脚本不会因多传字段 400。
 */
@Data
public class CheckoutDTO {

    @NotEmpty(message = "请选择要面交的商品")
    private List<Long> cartIds;

    /** 历史字段：忽略 */
    private Long pointId;

    /** 历史字段：忽略 */
    private String timeSlotStart;

    /** 历史字段：忽略 */
    private String timeSlotEnd;

    private String remark;
}
