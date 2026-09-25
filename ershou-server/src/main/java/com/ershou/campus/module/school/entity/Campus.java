package com.ershou.campus.module.school.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("campus")
public class Campus {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long schoolId;
    private String name;
    private Integer mapType;
    private String mapConfig;
    private Integer status;
    private LocalDateTime createdAt;
}
