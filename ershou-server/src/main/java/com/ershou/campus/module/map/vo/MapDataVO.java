package com.ershou.campus.module.map.vo;

import lombok.Data;

import java.util.List;

@Data
public class MapDataVO {
    private Integer mapType;
    private Object mapConfig;
    private List<TradePointVO> points;
}
