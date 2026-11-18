package com.ershou.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏夹分组（第 103 轮按契约迁入 order-service 的 {@code ershou_order} 库）。
 *
 * <p>旧单体只建表、代码未使用（功能清单问题：收藏夹分组未实现），本轮仅完成"数据域归属"——
 * 表与数据已随 {@code sql/split-order.sql} 迁移，暂不提供 CRUD 接口（无旧接口可迁移）。
 */
@Data
@TableName("favorite_folder")
public class FavoriteFolder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private Integer sort;

    private LocalDateTime createdAt;
}
