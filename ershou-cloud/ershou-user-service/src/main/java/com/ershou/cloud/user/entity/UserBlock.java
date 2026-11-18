package com.ershou.cloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拉黑关系（`ershou_user.user_block`，单向存储、判定时看双向）。
 *
 * <p>表结构与旧库 `ershou.user_block` 完全一致（`UNIQUE KEY uk_block_pair(user_id, blocked_user_id)`
 * 兜住并发重复拉黑），数据由 `ershou-cloud/sql/split-user.sql` 迁移。
 */
@Data
@TableName("user_block")
public class UserBlock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起拉黑的人 */
    private Long userId;

    /** 被拉黑的人 */
    private Long blockedUserId;

    private LocalDateTime createdAt;
}
