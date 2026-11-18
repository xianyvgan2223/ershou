package com.ershou.cloud.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.platform.entity.PlatformFlashSale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlatformFlashSaleMapper extends BaseMapper<PlatformFlashSale> {

    /**
     * 当前进行中的场次：status=2 且 now 落在 [start_at, end_at)。
     * 没有则返回 null（公开接口据此返回 null，设计 3.2）。
     */
    @Select("SELECT * FROM platform_flash_sale "
            + "WHERE campus_id = #{campusId} AND status = 2 "
            + "AND start_at <= NOW() AND end_at > NOW() "
            + "ORDER BY sort ASC, id DESC LIMIT 1")
    PlatformFlashSale selectCurrent(@Param("campusId") Long campusId);
}
