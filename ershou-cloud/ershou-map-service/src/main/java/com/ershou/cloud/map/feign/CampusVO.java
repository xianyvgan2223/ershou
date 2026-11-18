package com.ershou.cloud.map.feign;

import lombok.Data;

/**
 * user-service 校区视图（只取地图底图需要的字段）。
 *
 * <p>{@code mapConfig} 是 JSON 文本（`campus.map_config`），由本服务解析成对象塞进 `/map/points` 的响应，
 * 与单体 `MapDataVO.mapConfig` 的 JSON 结构保持一致（前端 `MapConfig` 类型直接可用）。
 */
@Data
public class CampusVO {
    private Long id;
    private Long schoolId;
    private String name;
    /** 1=自绘平面图 2=第三方地图（高德） */
    private Integer mapType;
    /** 底图配置 JSON 文本：center/zoom/pixelBounds/campusSizeMeters/source... */
    private String mapConfig;
    private Integer status;
}
