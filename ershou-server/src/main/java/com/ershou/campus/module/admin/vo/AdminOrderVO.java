package com.ershou.campus.module.admin.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 运营后台的面交单视图 */
@Data
public class AdminOrderVO {
    private Long id;
    private String orderNo;
    private String batchNo;
    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private Integer quantity;
    private BigDecimal dealPrice;
    private Long buyerId;
    private String buyerNickname;
    private Long sellerId;
    private String sellerNickname;
    private Long pointId;
    private String pointName;
    private LocalDateTime timeSlotStart;
    private LocalDateTime timeSlotEnd;
    private Integer status;
    private String statusText;
    private LocalDateTime verifiedAt;
    private LocalDateTime buyerConfirmAt;
    private LocalDateTime sellerConfirmAt;
    private String cancelReason;
    private LocalDateTime createdAt;
}
