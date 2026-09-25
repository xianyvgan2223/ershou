package com.ershou.campus.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.order.entity.MeetupOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MeetupOrderMapper extends BaseMapper<MeetupOrder> {

    /** 条件更新核销：保证一次性使用（并发兜底） */
    @Update("UPDATE meetup_order SET verified_at = #{now}, verified_by = #{userId} "
            + "WHERE id = #{orderId} AND status = 2 AND verified_at IS NULL")
    int markVerified(@Param("orderId") Long orderId, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Update("UPDATE meetup_order SET status = 3 WHERE id = #{orderId} AND status = 2")
    int markFinished(@Param("orderId") Long orderId);
}
