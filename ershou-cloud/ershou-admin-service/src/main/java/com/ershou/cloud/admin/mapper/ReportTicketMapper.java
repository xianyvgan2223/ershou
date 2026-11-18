package com.ershou.cloud.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.admin.entity.ReportTicket;
import org.apache.ibatis.annotations.Mapper;

/** 举报/仲裁工单 Mapper（本服务自己的库） */
@Mapper
public interface ReportTicketMapper extends BaseMapper<ReportTicket> {
}
