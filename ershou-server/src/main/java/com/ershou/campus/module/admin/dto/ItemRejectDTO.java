package com.ershou.campus.module.admin.dto;

import lombok.Data;

/** 商品驳回：reasonCode 取驳回原因模板，reason 为补充说明 */
@Data
public class ItemRejectDTO {
    private String reasonCode;
    private String reason;
}
