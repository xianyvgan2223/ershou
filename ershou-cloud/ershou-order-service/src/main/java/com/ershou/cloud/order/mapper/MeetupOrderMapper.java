package com.ershou.cloud.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.order.entity.MeetupOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面交单 Mapper（{@code ershou_order} 库）。
 *
 * <p>{@code @Mapper} + 启动类 {@code @MapperScan} 双保险；本切片只有基础 CRUD，
 * 用 MyBatis-Plus 的 {@code BaseMapper} 就够（无自定义 SQL，避免与单体 Mapper 纠缠）。
 */
@Mapper
public interface MeetupOrderMapper extends BaseMapper<MeetupOrder> {
}
