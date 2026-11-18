package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品服务内部视图（/inner/items/** 契约）。
 *
 * <p>字段是 item-service 云版 ItemVO/平台商品 VO 的超集，未使用字段忽略即可；
 * 时间字段由 item-service 统一序列化为 {@code yyyy-MM-dd HH:mm:ss}（Jackson 本地日期默认同样格式，
 * 这里用字符串接收避免两端口径差异导致解析失败）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InnerItemVO {

    private Long id;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer negotiable;
    private Integer conditionLevel;
    private String conditionText;
    private String coverUrl;
    /** item-service 可能返回 JSON 数组或已解析的 List，统一用 Object 接收后本地解析 */
    private Object images;
    private Long categoryId;
    private String categoryName;
    private Long campusId;
    private Long preferPointId;
    private Long platformPointId;
    private String platformPointName;
    private Long sellerId;
    private Integer sellerType;
    private Integer status;
    private String statusText;
    private Integer stock;
    private Integer isUnique;
    private Integer viewCount;
    private Integer favCount;
    private Integer saleCount;
    private String publishedAt;
    private String expireAt;
}
