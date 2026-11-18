package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 商品驳回：reasonCode 取驳回原因模板，reason 为补充说明 */
@Data
public class ItemRejectDTO {
    private String reasonCode;
    @Size(max = 200, message = "补充说明不能超过 200 字")
    private String reason;
}
