package com.ershou.campus.module.order.dto;

import lombok.Data;

@Data
public class OrderActionDTO {
    /** 拒绝/取消原因 */
    private String reason;
    /** 核销码 */
    private String code;
}
