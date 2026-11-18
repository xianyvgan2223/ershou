package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 举报工单处置入参（T107 新增接口，旧单体无对应实现）。
 *
 * <p>状态口径：1=待受理 2=处理中 3=已判定 4=申诉中 5=已结案
 */
@Data
public class ReportHandleDTO {

    /** 目标状态（默认 5 已结案）：2 处理中 / 3 已判定 / 5 已结案 */
    private Integer status = 5;

    /** 处置结论（必填，进审计日志） */
    @NotBlank(message = "请填写处置结论")
    @Size(max = 255, message = "处置结论不能超过 255 字")
    private String decision;

    /** 处罚措施（选填，如：警告 / 下架商品 / 封禁 7 天） */
    @Size(max = 255, message = "处罚措施不能超过 255 字")
    private String penalty;
}
