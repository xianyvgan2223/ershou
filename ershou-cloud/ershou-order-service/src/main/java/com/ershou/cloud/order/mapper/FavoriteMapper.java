package com.ershou.cloud.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.order.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;

/** 收藏 Mapper（{@code ershou_order.favorite}）。 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {
}
