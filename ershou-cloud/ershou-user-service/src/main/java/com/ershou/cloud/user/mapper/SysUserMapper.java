package com.ershou.cloud.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.user.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper（MyBatis-Plus；自定义 SQL 与单体 `SysUserMapper` 保持一致）。
 *
 * <p>当前 user-service 是 `ershou_user.sys_user` 的唯一写入方，所以信用分/状态等写操作
 * 全在本服务内闭环；order-service 完成面交后通过 `/inner/users/{id}/credit` 调这里加分。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 直接设定信用分（分数与等级一起写，避免重复计算；与单体 setCredit 一致） */
    @Update("UPDATE sys_user SET credit_score = #{score}, credit_level = #{level} WHERE id = #{userId}")
    int setCredit(@Param("userId") Long userId, @Param("score") int score, @Param("level") int level);
}
