package com.ershou.cloud.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.order.entity.CartItem;
import org.apache.ibatis.annotations.Mapper;

/** 购物车 Mapper（{@code ershou_order.cart_item}）。基础 CRUD 用 MyBatis-Plus。 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}
