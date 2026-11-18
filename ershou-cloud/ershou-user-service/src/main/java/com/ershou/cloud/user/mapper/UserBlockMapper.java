package com.ershou.cloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.user.entity.UserBlock;
import org.apache.ibatis.annotations.Mapper;

/** 拉黑关系 Mapper（MyBatis-Plus，无自定义 SQL） */
@Mapper
public interface UserBlockMapper extends BaseMapper<UserBlock> {
}
