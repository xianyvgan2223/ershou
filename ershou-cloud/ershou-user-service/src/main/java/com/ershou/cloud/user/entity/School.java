package com.ershou.cloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 学校（`ershou_user.school`，与单体 `module/school/entity/School` 一致） */
@Data
@TableName("school")
public class School {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String city;
    private Integer source;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
