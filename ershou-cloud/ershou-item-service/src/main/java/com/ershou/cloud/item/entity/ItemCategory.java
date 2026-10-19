package com.ershou.cloud.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 商品分类（库 {@code ershou_item.item_category}），列表/详情回填 {@code categoryName} 用。
 * 从单体 {@code ItemCategory} 复制，列与 init-cloud-databases.sql 一致。
 */
@Data
@TableName("item_category")
public class ItemCategory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String name;
    private String icon;
    private Integer sort;
    private Long schoolId;
    private Integer status;
}
