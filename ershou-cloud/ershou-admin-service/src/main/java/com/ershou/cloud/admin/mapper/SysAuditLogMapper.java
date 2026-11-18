package com.ershou.cloud.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.admin.entity.SysAuditLog;
import org.apache.ibatis.annotations.Mapper;

/** 操作审计日志 Mapper（本服务自己的库） */
@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
