package com.ershou.campus.module.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台秒杀场次（设计 2.2）。
 */
@Data
@TableName("platform_flash_sale")
public class PlatformFlashSale {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long campusId;
    private String title;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** 1=预告 2=进行中 3=结束 */
    private Integer status;
    private Integer sort;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
