package com.ershou.cloud.item.vo;

import lombok.Data;

/**
 * 面交点简版 VO（map-service 的 trade_point 经 OpenFeign 取回）。
 *
 * <p>item-service 不存点位数据（map-service 的 ershou_map 库），详情/卡片需要
 * {@code preferPointName/preferPointGuide} 时通过 {@code GET /inner/points/{id}} 补齐；
 * map-service 不可用时降级为 null（前端有兜底）。
 */
@Data
public class PointVO {
    private Long id;
    private String name;
    /** 面交指引文案（map-service 字段名 guide；旧单体叫 guideText） */
    private String guide;
    private Double longitude;
    private Double latitude;
    private Long campusId;
}
