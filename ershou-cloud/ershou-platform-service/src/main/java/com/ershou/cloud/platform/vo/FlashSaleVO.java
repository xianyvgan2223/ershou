package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀场次 VO（设计 3.2，字段与 Web `api/platform.ts#FlashSaleVO` 对齐）。
 * 无进行中场次时公开接口返回 {@code data:null}。
 */
@Data
public class FlashSaleVO {
    private Long id;
    private String title;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** 1=预告 2=进行中 3=结束 */
    private Integer status;
    /** 距结束剩余秒数（服务端算好，前端只做本地倒计时递减） */
    private Long remainSeconds;
    private List<FlashSaleItemVO> items;
}
