package com.ershou.campus.module.item.entity;

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
    private Integer status;
    private Integer auditResult;
    private String auditReason;
    private String rejectCode;
    private Long lockUserId;
    private LocalDateTime lockExpireAt;
    private LocalDateTime expireAt;
    private LocalDateTime publishedAt;
    private Integer viewCount;
    private Integer chatCount;
    private Integer favCount;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
