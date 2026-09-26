package com.ershou.campus.module.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.order.entity.MeetupOrder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面交单 Mapper。
 *
 * <p>核销相关的方法（markVerified / markFinished）已随"去掉核销码"一并删除：
 * 成交判定改为卖家点一次确认，状态流转走 {@code OrderService#confirm} 的普通更新。
 */
@Mapper
public interface MeetupOrderMapper extends BaseMapper<MeetupOrder> {
}
