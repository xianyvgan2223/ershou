package com.ershou.cloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/** 用户 Mapper（MyBatis-Plus，无自定义 SQL） */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
