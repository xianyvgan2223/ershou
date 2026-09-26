package com.ershou.campus.module.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderVO {
    private Long id;
    private String orderNo;
    private String batchNo;
    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private Integer quantity;
    private BigDecimal dealPrice;
    private Long pointId;
    private String pointName;
    private String pointGuide;
    private LocalDateTime timeSlotStart;
    private LocalDateTime timeSlotEnd;
    private Integer status;
    private String statusText;
    private Long buyerId;
    private String buyerName;
    private Long sellerId;
    private String sellerName;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
    private Boolean buyerConfirmed;
    private Boolean sellerConfirmed;
    /** 当前登录者在该单中的角色：buyer / seller */
    private String myRole;
}
