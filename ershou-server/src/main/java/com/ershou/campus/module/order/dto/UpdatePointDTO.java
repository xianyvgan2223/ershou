package com.ershou.campus.module.order.dto;

import lombok.Data;

/**
 * 改面交地点 / 时间（私聊页里买卖双方协商改地址用）。
 *
 * <p>pointId 必填（改地点）；两个时间字段都留空表示沿用原时间窗，
 * 都填表示一起改，只填一个按 validateSlot 的规则会被拒。
 */
@Data
public class UpdatePointDTO {
    private Long pointId;

    private String timeSlotStart;

    private String timeSlotEnd;
}
