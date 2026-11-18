package com.ershou.cloud.order.feign;

import lombok.Data;

/**
 * map-service 内部接口返回的面交点数据（契约：{@code GET /inner/points/{id}} → {id,name,guide,longitude,latitude,campusId}）。
 *
 * <p>面交单的 {@code point_snapshot} 由它组装（名称/指引/坐标），列表页的点位名也来自它。
 * 同时兼容字段别名（guide/guideText、longitude/lng、latitude/lat）。
 */
@Data
public class InnerPointVO {

    private Long id;

    private String name;

    private String guide;

    private String guideText;

    private Double longitude;

    private Double latitude;

    private Double lng;

    private Double lat;

    private Long campusId;

    private Integer safetyScore;

    public String guideText() {
        return guideText != null ? guideText : guide;
    }

    public Double longitude() {
        return longitude != null ? longitude : lng;
    }

    public Double latitude() {
        return latitude != null ? latitude : lat;
    }
}
