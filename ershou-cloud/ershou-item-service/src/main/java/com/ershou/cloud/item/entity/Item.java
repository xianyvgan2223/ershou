package com.ershou.cloud.item.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("item")
public class Item {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sellerId;
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
    private String images;
    private String videoUrl;
    private Integer tradeMode;
    private Long preferPointId;
    /**
     * 卖家类型：1=个人卖家，2=平台自营（官方代卖）。
     *
     * <p>列 {@code seller_type TINYINT NOT NULL DEFAULT 1} 由 {@code sql/migration-platform.sql} 添加；
     * 个人商品恒为 1，平台商品由 platform 模块直插为 2。本模块只负责读出并透出到 VO
     * （首页/搜索/详情的「官方代卖」角标数据源），不参与商品状态机与审核流程。
     */
    private Integer sellerType;
    /**
     * 平台代卖/自提点 id（仅平台商品有值，个人商品为 null）。
     *
     * <p>列 {@code platform_point_id} 同样由 platform 迁移脚本添加。点位名称属于 platform 模块，
     * 本模块不回表取名（避免 item → platform 的跨模块依赖），VO 里只透出 id。
     */
    private Long platformPointId;
    private Integer status;
    private Integer auditResult;
    private String auditReason;
    private String rejectCode;
    private Long lockUserId;
    private LocalDateTime lockExpireAt;
    /**
     * 上架时（审核员已看过）的标题与描述哈希快照，见 {@code item_review} 表。
     * 在售期间这两者与当前内容对不上 = 卖家改过名 → 重新进「上架审核」页。
     */
    private String lastLiveTitle;
    private String lastLiveDescHash;
    private LocalDateTime expireAt;
    private LocalDateTime publishedAt;
    private Integer viewCount;
    private Integer chatCount;
    private Integer favCount;
    private Integer version;
    /** 第 5 轮新增/第 T102 延续：已售件数，由 RabbitMQ order.created 异步累加（演示 MQ 解耦） */
    private Integer saleCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
