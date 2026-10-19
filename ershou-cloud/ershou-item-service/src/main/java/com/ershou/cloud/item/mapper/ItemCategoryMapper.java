package com.ershou.cloud.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.item.entity.ItemCategory;
import org.apache.ibatis.annotations.Mapper;

/** 商品分类 Mapper（只读） */
@Mapper
public interface ItemCategoryMapper extends BaseMapper<ItemCategory> {
}
