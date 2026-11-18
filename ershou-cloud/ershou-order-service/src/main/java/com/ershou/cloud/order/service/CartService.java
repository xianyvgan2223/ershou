package com.ershou.cloud.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.order.dto.CartVO;
import com.ershou.cloud.order.entity.CartItem;
import com.ershou.cloud.order.feign.InnerItemVO;
import com.ershou.cloud.order.feign.InnerPointVO;
import com.ershou.cloud.order.feign.InnerUserVO;
import com.ershou.cloud.order.feign.ItemClient;
import com.ershou.cloud.order.feign.PointClient;
import com.ershou.cloud.order.feign.UserClient;
import com.ershou.cloud.order.mapper.CartItemMapper;
import com.ershou.cloud.order.util.Texts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 购物车服务（第 103 轮从单体 {@code module.cart.service.CartService} 迁入 order-service）。
 *
 * <p>与单体的唯一区别：商品数据不再直连 item 库，改为 Feign {@code GET /inner/items/{id}}；
 * 商品服务不可用时加购/改数量返回 90001（无法校验），列表对失效条目降级为「已失效」。
 * 购物车行落在本服务自有库 {@code ershou_order.cart_item}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemMapper cartMapper;
    private final ItemClient itemClient;
    private final UserClient userClient;
    private final PointClient pointClient;

    /** 加购：按结算口径校验（已售出 30002 / 已锁定 30004 / 不可约 10001 / 自己的商品 10001），数量夹取 */
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, Long itemId, Integer quantity) {
        InnerItemVO item = requireItem(itemId);
        validateSellable(item, userId);
        int qty = clampQuantity(item, quantity);
        CartItem exist = cartMapper.selectOne(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId).eq(CartItem::getItemId, itemId));
        if (exist != null) {
            exist.setQuantity(qty);
            cartMapper.updateById(exist);
        } else {
            CartItem ci = new CartItem();
            ci.setUserId(userId);
            ci.setItemId(itemId);
            ci.setSellerId(item.getSellerId());
            ci.setQuantity(qty);
            ci.setSelected(1);
            cartMapper.insert(ci);
        }
        log.info("[cart][add][{}][{}] qty={}", userId, itemId, qty);
    }

    /** 改数量：归属校验 + 同一套夹取（孤品恒 1、不超库存） */
    public void update(Long userId, Long cartId, Integer quantity) {
        CartItem ci = requireOwn(userId, cartId);
        InnerItemVO item = requireItem(ci.getItemId());
        ci.setQuantity(clampQuantity(item, quantity));
        cartMapper.updateById(ci);
    }

    /** 移除：不是本人的记录静默返回（不泄露存在性） */
    public void delete(Long userId, Long cartId) {
        CartItem ci = cartMapper.selectById(cartId);
        if (ci == null || !Objects.equals(ci.getUserId(), userId)) {
            return;
        }
        cartMapper.deleteById(cartId);
    }

    /** 按卖家分组（前端 CartView 的分组结构） */
    public List<CartVO> listGrouped(Long userId) {
        List<CartItem> rows = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId).orderByDesc(CartItem::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, InnerItemVO> items = batchItems(rows.stream().map(CartItem::getItemId).distinct().toList());
        Set<Long> pointIds = items.values().stream().map(InnerItemVO::getPreferPointId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> pointNames = pointNames(pointIds);
        Map<Long, String> sellerNames = sellerNames(rows.stream().map(CartItem::getSellerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        Map<Long, List<CartItem>> bySeller = new LinkedHashMap<>();
        for (CartItem row : rows) {
            bySeller.computeIfAbsent(row.getSellerId(), k -> new ArrayList<>()).add(row);
        }
        List<CartVO> result = new ArrayList<>();
        bySeller.forEach((sellerId, list) -> {
            CartVO group = new CartVO();
            group.setSellerId(sellerId);
            group.setSellerName(sellerNames.getOrDefault(sellerId, "卖家"));
            group.setItems(list.stream().map(row -> {
                CartVO.Row r = new CartVO.Row();
                r.setCartId(row.getId());
                r.setItemId(row.getItemId());
                r.setQuantity(row.getQuantity());
                InnerItemVO item = items.get(row.getItemId());
                if (item != null) {
                    r.setTitle(item.getTitle());
                    r.setCoverUrl(item.getCoverUrl());
                    r.setPrice(item.getPrice());
                    r.setStatus(item.getStatus());
                    r.setStatusText(Texts.itemStatus(item.getStatus()));
                    r.setPreferPointId(item.getPreferPointId());
                    r.setPreferPointName(pointNames.get(item.getPreferPointId()));
                } else {
                    // 商品被删除或商品服务不可用：按"已失效"展示，前端置灰不可结算
                    r.setTitle("已失效的商品");
                    r.setStatus(4);
                    r.setStatusText("已失效");
                }
                return r;
            }).toList());
            result.add(group);
        });
        return result;
    }

    /** 结算用：只取本人购物车里指定的 cartIds（越权 id 自动忽略） */
    public List<CartItem> rawByIds(Long userId, List<Long> cartIds) {
        if (cartIds == null || cartIds.isEmpty()) {
            return List.of();
        }
        return cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId).in(CartItem::getId, cartIds));
    }

    public void removeByIds(java.util.Collection<Long> cartIds) {
        if (cartIds != null && !cartIds.isEmpty()) {
            cartMapper.deleteByIds(cartIds);
        }
    }

    // ---------------------------------------------------------------- 校验/工具

    private CartItem requireOwn(Long userId, Long cartId) {
        CartItem ci = cartMapper.selectById(cartId);
        if (ci == null || !Objects.equals(ci.getUserId(), userId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "购物车记录不存在");
        }
        return ci;
    }

    private InnerItemVO requireItem(Long itemId) {
        Result<InnerItemVO> result;
        try {
            result = itemClient.get(itemId);
        } catch (Exception e) {
            log.error("[cart][feign][item={}] 调 item-service 失败: {}", itemId, e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            if (result != null && result.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
                throw new BizException(ResultCode.ITEM_NOT_FOUND);
            }
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (result.getData() == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return result.getData();
    }

    private void validateSellable(InnerItemVO item, Long userId) {
        if (item.getStatus() != null && item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD);
        }
        if (item.getStatus() != null && item.getStatus() == 3) {
            throw new BizException(ResultCode.ITEM_LOCKED, "商品已被其他同学约走，看看同款？");
        }
        if (item.getStatus() == null || item.getStatus() != 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品当前不可约面交");
        }
        if (Objects.equals(item.getSellerId(), userId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能把自己发布的商品加入购物车");
        }
    }

    /** 数量夹取：孤品恒 1；非孤品超库存直接报错（与单体一致） */
    private int clampQuantity(InnerItemVO item, Integer quantity) {
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        if (item.getIsUnique() != null && item.getIsUnique() == 1) {
            return 1;
        }
        Integer stock = item.getStock();
        if (stock != null && stock > 0 && qty > stock) {
            throw new BizException(ResultCode.PARAM_ERROR, "库存只剩 " + stock + " 件");
        }
        return qty;
    }

    /** 批量取商品：优先 batch，接口不可用时逐条 get 降级（任一失败返回 null 用占位） */
    private Map<Long, InnerItemVO> batchItems(List<Long> itemIds) {
        Map<Long, InnerItemVO> map = new HashMap<>();
        if (itemIds.isEmpty()) {
            return map;
        }
        try {
            Result<List<InnerItemVO>> batch = itemClient.batch(itemIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
            if (batch != null && batch.getCode() == ResultCode.SUCCESS.getCode() && batch.getData() != null) {
                batch.getData().forEach(i -> map.put(i.getId(), i));
                return map;
            }
        } catch (Exception e) {
            log.warn("[cart][batch][{}] 批量取商品失败，逐条降级: {}", itemIds, e.getMessage());
        }
        for (Long id : itemIds) {
            try {
                Result<InnerItemVO> r = itemClient.get(id);
                if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                    map.put(id, r.getData());
                }
            } catch (Exception ignore) {
                // 商品服务不可用：用占位
            }
        }
        return map;
    }

    private Map<Long, String> pointNames(Set<Long> pointIds) {
        Map<Long, String> map = new HashMap<>();
        for (Long pointId : pointIds) {
            try {
                Result<InnerPointVO> r = pointClient.get(pointId);
                if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                    map.put(pointId, r.getData().getName());
                }
            } catch (Exception ignore) {
                // 地图服务不可用：点位名留空
            }
        }
        return map;
    }

    private Map<Long, String> sellerNames(Set<Long> sellerIds) {
        Map<Long, String> map = new HashMap<>();
        if (sellerIds.isEmpty()) {
            return map;
        }
        try {
            Result<List<InnerUserVO>> r = userClient.batch(sellerIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
            if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                for (InnerUserVO u : r.getData()) {
                    map.put(u.getId(), u.getShopName() != null ? u.getShopName() : u.getNickname());
                }
            }
        } catch (Exception e) {
            log.warn("[cart][users] 批量取卖家失败，降级为'卖家': {}", e.getMessage());
        }
        return map;
    }
}
