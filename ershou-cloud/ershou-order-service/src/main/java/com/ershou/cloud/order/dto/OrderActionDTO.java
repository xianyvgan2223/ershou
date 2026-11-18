package com.ershou.cloud.order.dto;

import lombok.Data;

/** 拒绝/取消原因（与旧单体 {@code OrderActionDTO} 一致；历史字段 code 为已删除的核销码，保留兼容）。 */
@Data
public class OrderActionDTO {

    private String reason;

    /** 历史字段（核销码已删除，忽略） */
    private String code;
}
