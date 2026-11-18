package com.ershou.cloud.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 运营台 · 秒杀场次保存入参（设计 3.4 `POST/PUT /flash-sales`）。
 * 字段与 Web `api/platform-console.ts#ConsoleFlashSaleSaveParams` 对齐。
 *
 * <p>时间用 String 接收（前端日期选择器/浏览器 JSON 格式不一致，见 {@code PlatformTimeUtils}）；
 * campusId 缺省时按设计取默认校区 2。
 */
@Data
public class FlashSaleSaveDTO {

    /** 不传 = 默认校区 2 */
    private Long campusId;

    @NotBlank(message = "场次标题不能为空")
    private String title;

    @NotBlank(message = "开始时间不能为空")
    private String startAt;

    @NotBlank(message = "结束时间不能为空")
    private String endAt;

    /** 1=预告 2=进行中 3=结束；新建缺省 1，编辑空则保持原状态 */
    private Integer status;

    private Integer sort;
}
