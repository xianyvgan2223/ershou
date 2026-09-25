package com.ershou.campus.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Update("UPDATE sys_user SET credit_score = credit_score + #{delta}, "
            + "credit_level = CASE WHEN credit_score + #{delta} >= 95 THEN 1 "
            + "WHEN credit_score + #{delta} >= 80 THEN 2 WHEN credit_score + #{delta} >= 60 THEN 3 ELSE 4 END "
            + "WHERE id = #{userId}")
    int addCredit(@Param("userId") Long userId, @Param("delta") int delta);
}
