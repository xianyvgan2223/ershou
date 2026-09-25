package com.ershou.campus.module.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 信用分流水（信用分每一次变动的可追溯记录） */
@Data
@TableName("credit_log")
public class CreditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer changeValue;
    private Integer beforeScore;
    private Integer afterScore;
    private String reason;
    private String bizType;
    private Long bizId;
    private Long operatorId;
    private LocalDateTime createdAt;
}
