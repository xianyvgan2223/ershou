package com.ershou.campus.module.platform.vo;

import lombok.Data;

/**
 * 平台专区概览（设计 3.2，字段与 Web `api/platform.ts#PlatformOverviewVO` 对齐）。
 */
@Data
public class PlatformOverviewVO {
    /** 在售平台商品数 */
    private Long platformItemCount;
    /** 启用中的代卖/自提点数 */
    private Long pointCount;
    /** 可领券数（启用且进行中） */
    private Long couponCount;
    /** 当前进行中的秒杀场次 id；无场次为 null */
    private Long flashSaleId;
    private String flashSaleTitle;
    /** 1=预告 2=进行中 3=结束；无场次为 null */
    private Integer flashSaleStatus;
}
