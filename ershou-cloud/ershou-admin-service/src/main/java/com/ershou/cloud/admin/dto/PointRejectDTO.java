package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 点位审核驳回 */
@Data
public class PointRejectDTO {
    private String reasonCode;
    @Size(max = 200, message = "补充说明不能超过 200 字")
    private String reason;
}
