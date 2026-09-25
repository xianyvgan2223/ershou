package com.ershou.campus.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Update("UPDATE sys_user SET "
            // 先算等级、再改分数：MySQL 单表 UPDATE 从左到右取值，反过来的话 CASE 里的 credit_score 已被叠加过
            + "credit_level = CASE WHEN credit_score + #{delta} >= 95 THEN 1 "
            + "WHEN credit_score + #{delta} >= 80 THEN 2 WHEN credit_score + #{delta} >= 60 THEN 3 ELSE 4 END, "
            + "credit_score = GREATEST(credit_score + #{delta}, 0) "
            + "WHERE id = #{userId}")
    int addCredit(@Param("userId") Long userId, @Param("delta") int delta);

    /** 运营直接设定信用分（分数与等级一起写，避免重复计算） */
    @Update("UPDATE sys_user SET credit_score = #{score}, credit_level = #{level} WHERE id = #{userId}")
    int setCredit(@Param("userId") Long userId, @Param("score") int score, @Param("level") int level);
}
