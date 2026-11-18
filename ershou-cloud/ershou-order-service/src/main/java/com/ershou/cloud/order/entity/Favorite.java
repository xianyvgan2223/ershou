package com.ershou.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏（第 103 轮从单体迁入 order-service）。库：{@code ershou_order}。
 * 唯一索引 {@code uk_fav_user_item(user_id,item_id)} 兜底并发重复收藏。
 */
@Data
@TableName("favorite")
public class Favorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long itemId;

    /** 收藏夹分组（历史字段，本轮无分组接口；表已迁入 ershou_order） */
    private Long folderId;

    private LocalDateTime createdAt;
}
