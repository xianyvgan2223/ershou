package com.ershou.cloud.order.dto;

import lombok.Data;

/**
 * 改面交地点/时间请求（与旧单体 {@code module.order.dto.UpdatePointDTO} JSON 一致）。
 *
 * <p>旧单体语义：只有**卖家**能改；pointId 必填；两个时间字段都留空 = 沿用原时间窗，
 * 都填 = 一起改（单侧留空会被 validateSlot 拒绝）。
 */
@Data
public class UpdatePointDTO {

    private Long pointId;

    private String timeSlotStart;

    private String timeSlotEnd;
}
