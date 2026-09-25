package com.ershou.campus.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("meetup_order")
public class MeetupOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private String batchNo;
    private Long buyerId;
    private Long sellerId;
    private Long itemId;
    private String itemTitle;
    private String itemCover;
    private Integer quantity;
    private BigDecimal dealPrice;
    private Long pointId;
    private String pointSnapshot;
    private LocalDateTime timeSlotStart;
    private LocalDateTime timeSlotEnd;
    private Integer status;
    private String verifyCode;
    private LocalDateTime verifyExpireAt;
    private LocalDateTime verifiedAt;
    private Long verifiedBy;
    private LocalDateTime buyerConfirmAt;
    private LocalDateTime sellerConfirmAt;
    private Integer rescheduleCount;
    private Long cancelBy;
    private String cancelReason;
    private Long noshowUserId;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
