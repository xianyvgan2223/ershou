package com.ershou.cloud.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.admin.entity.AiAuditRecord;
import org.apache.ibatis.annotations.Mapper;

/** AI 审核记录 Mapper（本服务自己的库，只读展示为主） */
@Mapper
public interface AiAuditRecordMapper extends BaseMapper<AiAuditRecord> {
}
