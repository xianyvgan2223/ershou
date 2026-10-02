package com.ershou.campus.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 上架审核记录（运营后台「上架审核」页的数据来源）。
 *
 * <p>与 {@code item} 是<b>多对一</b>的语义：一个商品只保留一条"当前"记录（{@code uk_item}），
 * 重新入队时在这条记录上累加 {@code revision}——审核员关心的是"这件商品现在这一版内容看过没有"，
 * 而不是历史上被审过几次（那些走 {@code sys_audit_log}）。
 */
@Data
@TableName("item_review")
public class ItemReview {

    /** 待审核：会出现在「待审核」列表里 */
    public static final int STATUS_PENDING = 0;
    /** 已审核：默认列表不再显示，是审核员的"处理完毕"归档态 */
    public static final int STATUS_DONE = 1;

    /** 新上架（发布后立刻在售，同时进审核页） */
    public static final int TYPE_NEW = 1;
    /** 在售期间改名 / 改描述：内容变了，等于新内容，重新回到审核页 */
    public static final int TYPE_RENAMED = 2;
    /** 已下架 / 已过期后重新上架 */
    public static final int TYPE_RELIST = 3;
    /** 违规下架后二次申请上架：这一条必须审核通过才可见 */
    public static final int TYPE_VIOLATION_RELIST = 4;
    /** 存量导入（上线时库里已有的在售商品，不打扰审核员） */
    public static final int TYPE_LEGACY = 5;

    /** 处置动作：已审核（看过，没问题） */
    public static final int ACTION_REVIEWED = 1;
    /** 处置动作：直接下架 */
    public static final int ACTION_OFFLINE = 2;

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Long sellerId;
    private String title;
    private Integer reviewType;
    private Integer revision;
    private Integer status;
    private Integer reviewAction;
    private LocalDateTime queuedAt;
    private LocalDateTime firstQueuedAt;
    private LocalDateTime contentChangedAt;
    private Long reviewerId;
    private LocalDateTime claimedAt;
    private LocalDateTime reviewedAt;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
