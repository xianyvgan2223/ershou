package com.ershou.cloud.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体（云 item-service，库 {@code ershou_item.item}）。
 *
 * <p>从单体 {@code module/item/entity/Item.java} 复制，列集合与
 * {@code ershou-cloud/sql/init-cloud-databases.sql} 的 item 建表完全一致（含第 5 轮新增的 {@code sale_count}）。
 * 本服务**只读**商品字段 + 写库存锁定/销量累加，不写发布/审核/上下架（仍由单体 8082 之外的写路径负责）。
 */
@Data
@TableName("item")
public class Item {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sellerId;
    /** 1=个人卖家 2=平台自营（官方代卖）——Web 首页「官方代卖」角标数据源 */
    private Integer sellerType;
    private Long platformPointId;
    private Long schoolId;
    private Long campusId;
    private String title;
    private String description;
    private Long categoryId;
    private Integer conditionLevel;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer negotiable;
    private Integer stock;
    private Integer isUnique;
    private String coverUrl;
    /** JSON 数组字符串，读取后转 List<String> */
    private String images;
    private String videoUrl;
    private Integer tradeMode;
    private Long preferPointId;
    /** 0=草稿 1=审核中 2=在售 3=已锁定 4=已售出 5=已下架 6=审核驳回 7=已过期 8=违规下架 */
    private Integer status;
    private Integer auditResult;
    private String auditReason;
    private String rejectCode;
    private Long lockUserId;
    private LocalDateTime lockExpireAt;
    private String lastLiveTitle;
    private String lastLiveDescHash;
    private LocalDateTime expireAt;
    private LocalDateTime publishedAt;
    private Integer viewCount;
    private Integer chatCount;
    private Integer favCount;
    private Integer version;
    /** 第 5 轮新增：已售件数，由 RabbitMQ order.created 异步累加（演示 MQ 解耦） */
    private Integer saleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
