package com.ershou.campus.module.school.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

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
