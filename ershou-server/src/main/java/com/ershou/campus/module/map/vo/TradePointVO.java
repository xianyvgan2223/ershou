package com.ershou.campus.module.map.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class TradePointVO {
    private Long id;
    private String name;
    private Integer type;
    private BigDecimal coordX;
    private BigDecimal coordY;
    private BigDecimal lng;
    private BigDecimal lat;
    private Integer safetyScore;
    private List<String> safeTags;
    private List<String> availablePeriod;
    private String guideText;
    /** 审核状态：0=待审 1=通过 2=驳回；待审点位仅提交者本人可见（发布页用来标注"审核中"） */
    private Integer auditStatus;
    /** 来源：1=官方 2=用户提交 3=高德搜索落库（前端据此区分"搜出来的地名"） */
    private Integer source;
    /**
     * 该点位上"还有戏"的商品数（草稿/审核中/在售/已锁定）—— 地图气泡用它。
     *
     * <p>注意它**含审核中的商品**：那件商品在业务上属于这个点位，但买家在商品列表里
     * 看不到。所以展示"点开能看到几件"时要用 {@link #onSaleCount}。
     */
    private Integer itemCount;
    /**
     * 该点位上**买家真的看得到**的在售商品数。
     *
     * <p>与 {@link #itemCount} 分开是为了避免"气泡写着 11 件、点开小窗只有 10 件"
     * 这种自相矛盾的展示（差的那些还在审核中）。
     */
    private Integer onSaleCount;
    private BigDecimal minPrice;
}
