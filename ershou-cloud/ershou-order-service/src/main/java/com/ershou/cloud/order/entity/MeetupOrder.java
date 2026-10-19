package com.ershou.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面交单实体（从单体 {@code com.ershou.campus.module.order.entity.MeetupOrder} 复制）。
 *
 * <p>库：{@code ershou_order}（独立 schema/账号，见设计 2.1），表结构与单体一致
 * （{@code ershou-cloud/sql/init-cloud-databases.sql} 的 {@code meetup_order}）。
 * 字段保持与表列一一对应，方便后续把单体其它面交单能力（接受/拒绝/成交）逐步迁过来。
 */
@Data
@TableName("meetup_order")
public class MeetupOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务单号（yyyyMMddHHmmss + 4 位随机） */
    private String orderNo;

    /** 合并面交行程号（本轮云切片单件下单，不使用；保留列兼容单体） */
    private String batchNo;

    private Long buyerId;

    private Long sellerId;

    private Long itemId;

    /** 下单时的商品标题快照（Feign 不可用时的兜底展示） */
    private String itemTitle;

    /** 下单时的商品封面快照 */
    private String itemCover;

    private Integer quantity;

    /** 成交价（本轮 = 下单时的商品价格） */
    private BigDecimal dealPrice;

    /** 面交点（本轮云切片不下单时传，保持 null） */
    private Long pointId;

    private String pointSnapshot;

    private LocalDateTime timeSlotStart;

    private LocalDateTime timeSlotEnd;

    /** 1=待卖家确认 2=待面交 3=已完成 4=已取消 5=已失效 6=爽约 7=纠纷中（云切片创建即 2） */
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
