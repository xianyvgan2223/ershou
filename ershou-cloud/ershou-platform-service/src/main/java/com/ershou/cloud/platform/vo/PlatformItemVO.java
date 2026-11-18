package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台商品 VO（设计 3.2，字段与 Web `api/platform.ts#PlatformItemVO` 对齐）。
 */
@Data
public class PlatformItemVO {
    private Long id;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String coverUrl;
    /** item.images 是 JSON 列，这里解析成数组返回 */
    private List<String> images;
    private Long categoryId;
    private String categoryName;
    private Integer conditionLevel;
    private Integer stock;
    /** 与个人商品同一套状态码：2 在售 / 3 已锁定 / 4 已售出 … */
    private Integer status;
    private String statusText;
    private LocalDateTime publishedAt;
    /** 平台代卖点 id / 名称（对应 item.platform_point_id，不是 trade_point.id） */
    private Long pointId;
    private String pointName;
    /** 固定 2（平台自营） */
    private Integer sellerType;
    /** 秒杀价 / 秒杀剩余库存：非秒杀商品为 null */
    private BigDecimal seckillPrice;
    private Integer seckillStock;
}
