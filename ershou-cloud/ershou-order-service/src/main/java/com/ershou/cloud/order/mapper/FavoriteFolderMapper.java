package com.ershou.cloud.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.order.entity.FavoriteFolder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏夹 Mapper（{@code ershou_order.favorite_folder}）。
 * 旧单体只有表没有接口，本轮保留 Mapper 以便后续实现分组时直接使用。
 */
@Mapper
public interface FavoriteFolderMapper extends BaseMapper<FavoriteFolder> {
}
