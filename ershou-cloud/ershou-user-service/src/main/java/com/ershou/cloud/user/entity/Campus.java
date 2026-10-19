package com.ershou.cloud.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 校区（`ershou_user.campus`，与单体 `module/school/entity/Campus` 一致）。
 *
 * <p>`mapType/mapConfig` 是地图底图配置，user-service 只透传给前端（前端地图页用得到），
 * 不做任何解析。
 */
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
