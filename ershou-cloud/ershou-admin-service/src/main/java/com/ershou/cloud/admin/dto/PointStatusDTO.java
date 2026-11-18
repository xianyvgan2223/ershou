package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 启用/停用（下架）面交点 */
@Data
public class PointStatusDTO {
    @NotNull(message = "请指定点位状态")
    private Integer status;
    @Size(max = 120, message = "原因不能超过 120 个字")
    private String reason;
}
