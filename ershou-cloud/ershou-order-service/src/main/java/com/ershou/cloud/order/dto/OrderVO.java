package com.ershou.cloud.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面交单 VO（第 103 轮全量拆分：字段与旧单体 {@code module.order.vo.OrderVO} 完全对齐，
 * 前端「我的面交」/私聊交易卡片零改动）。
 */
@Data
public class OrderVO {

    /** 订单 id（同时给 id 与 orderId，兼容前端两种叫法） */
    private Long id;

    private Long orderId;

    private String orderNo;

    private String batchNo;

    private Long itemId;

    /** 商品标题：优先用 item-service 实时数据，降级用下单快照 */
    private String itemTitle;

    /** 商品封面：同上 */
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

    /** 取消/失效原因（终态单前端红条展示） */
    private String cancelReason;

    private LocalDateTime createdAt;

    private LocalDateTime verifiedAt;

    /** 买家点过「我收到了」（只留痕，成交由卖家确认；平台单买家确认即完成） */
    private Boolean buyerConfirmed;

    private Boolean sellerConfirmed;

    /** 当前登录者在该单中的角色：buyer / seller（管理端/内部接口为 null） */
    private String myRole;
}
