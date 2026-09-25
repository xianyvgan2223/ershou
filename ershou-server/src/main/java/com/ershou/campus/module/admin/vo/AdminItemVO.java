package com.ershou.campus.module.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 运营后台的商品视图（商品管理 + 审核工作台共用） */
@Data
public class AdminItemVO {
    private Long id;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer conditionLevel;
    private String conditionText;
    private String coverUrl;
    private List<String> images;
    private Long categoryId;
    private String categoryName;
    private Long campusId;
    private String campusName;
    private Long preferPointId;
    private String preferPointName;

    private Long sellerId;
    private String sellerNickname;
    /** 脱敏手机号，便于运营核实 */
    private String sellerPhone;
    private Integer sellerCreditScore;

    private Integer status;
    private String statusText;
    private Integer auditResult;
    private String auditReason;
    private String rejectCode;

    private Integer viewCount;
    private Integer favCount;
    private Integer stock;
    private LocalDateTime publishedAt;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
}
