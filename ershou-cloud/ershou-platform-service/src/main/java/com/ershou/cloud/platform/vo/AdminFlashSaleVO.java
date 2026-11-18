package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营台 · 秒杀场次（GET /platform/admin/flash-sales）。
 * 字段与 Web `api/platform-console.ts#ConsoleAdminFlashSaleVO` 对齐。
 */
@Data
public class AdminFlashSaleVO {
    private Long id;
    private String title;
    /** 1=预告 2=进行中 3=结束 */
    private Integer status;
    private String statusText;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer sort;
    private Long campusId;
    private List<AdminFlashSaleItemVO> items;
}
