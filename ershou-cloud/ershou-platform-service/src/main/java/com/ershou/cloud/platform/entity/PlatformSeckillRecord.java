package com.ershou.cloud.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 抢购成功记录（设计 2.2）：DB 真值，用于预热重算 Redis 库存 / 判断"已抢过"。
 * 唯一键 {@code (item_id, user_id)} 防同一用户重复抢到。
 */
@Data
@TableName("platform_seckill_record")
public class PlatformSeckillRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long itemId;
    private Long userId;
    private Long orderId;
    private BigDecimal price;
    private LocalDateTime createdAt;
}
