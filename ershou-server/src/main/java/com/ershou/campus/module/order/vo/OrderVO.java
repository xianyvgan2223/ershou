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
    /**
     * 取消 / 失效原因。
     *
     * <p>这个字段一直只存在库里和私聊消息里，"我的面交"卡片只看得到一个光秃秃的
     * 「已失效」—— 商品被卖给别人、被下架导致自动作废时，买家不知道为什么。
     * 现在随列表一起下发，前端在终态单上直接展示。
     */
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime verifiedAt;
    private Boolean buyerConfirmed;
    private Boolean sellerConfirmed;
    /** 当前登录者在该单中的角色：buyer / seller */
    private String myRole;
}
