package com.ershou.campus.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 拉黑关系（单向存储，判定时看双向）。唯一索引兜住并发重复拉黑。 */
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
