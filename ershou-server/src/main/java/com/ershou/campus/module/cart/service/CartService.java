package com.ershou.campus.module.cart.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.cart.entity.CartItem;
import com.ershou.campus.module.cart.mapper.CartItemMapper;
import com.ershou.campus.module.cart.vo.CartVO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemMapper cartMapper;
    private final ItemMapper itemMapper;
    private final UserService userService;
    private final TradePointService tradePointService;

    /**
     * 加入购物车。
     *
     * <p>以前这里只挡了"已售出"，于是**已锁定 / 自己的商品 / 超库存数量**都能加进来：
     * 前端把它显示成可结算，用户勾选后到结算才失败（"商品已被其他同学约走"），白跑一趟。
     * 现在加购就按结算的标准校验一遍（复用 OrderService 的同一套判断口径）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, Long itemId, Integer quantity) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (item.getStatus() != null && item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD);
        }
        if (item.getStatus() != null && item.getStatus() == 3) {
            throw new BizException(ResultCode.ITEM_LOCKED, "商品已被其他同学约走，看看同款？");
        }
        if (item.getStatus() == null || item.getStatus() != 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品当前不可约面交");
        }
        // 自己的商品加进购物车没有意义，结算时也一定会被拒 —— 提前拦住
        if (Objects.equals(item.getSellerId(), userId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能把自己发布的商品加入购物车");
        }
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

    /**
     * 数量夹取：孤品恒为 1；其余的夹在 [1, 库存]。
     *
     * <p>以前只判了 `quantity < 1`，超过库存也照存（实测库存 5 能加 99 件），
     * 结算时按那个数量建单，数量是错的。
     */
    private int clampQuantity(Item item, Integer quantity) {
        int qty = quantity == null || quantity < 1 ? 1 : quantity;
        if (item.getIsUnique() != null && item.getIsUnique() == 1) {
            return 1; // 孤品只能 1 件
        }
        Integer stock = item.getStock();
        if (stock != null && stock > 0 && qty > stock) {
            throw new BizException(ResultCode.PARAM_ERROR, "库存只剩 " + stock + " 件");
        }
        return qty;
    }

    public void update(Long userId, Long cartId, Integer quantity) {
        CartItem ci = cartMapper.selectById(cartId);
        if (ci == null || !Objects.equals(ci.getUserId(), userId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "购物车记录不存在");
        }
        // 改数量也要走同一套夹取：孤品不能变 3 件，也不能超库存
        Item item = itemMapper.selectById(ci.getItemId());
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        ci.setQuantity(clampQuantity(item, quantity));
        cartMapper.updateById(ci);
    }

    public void delete(Long userId, Long cartId) {
        CartItem ci = cartMapper.selectById(cartId);
        if (ci == null || !Objects.equals(ci.getUserId(), userId)) {
            return;
        }
        cartMapper.deleteById(cartId);
    }

    /** 按卖家分组，便于"合并面交" */
    public List<CartVO> listGrouped(Long userId) {
        List<CartItem> rows = cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId).orderByDesc(CartItem::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = rows.stream().map(CartItem::getItemId).distinct().toList();
        Map<Long, Item> items = itemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        Set<Long> pointIds = items.values().stream().map(Item::getPreferPointId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, TradePoint> points = tradePointService.mapByIds(pointIds);
        Map<Long, SysUser> sellers = userService.mapByIds(rows.stream().map(CartItem::getSellerId).collect(Collectors.toSet()));

        Map<Long, List<CartItem>> bySeller = new LinkedHashMap<>();
        for (CartItem row : rows) {
            bySeller.computeIfAbsent(row.getSellerId(), k -> new ArrayList<>()).add(row);
        }
        List<CartVO> result = new ArrayList<>();
        bySeller.forEach((sellerId, list) -> {
            CartVO group = new CartVO();
            group.setSellerId(sellerId);
            SysUser seller = sellers.get(sellerId);
            group.setSellerName(seller == null ? "卖家" : (seller.getShopName() != null ? seller.getShopName() : seller.getNickname()));
            group.setItems(list.stream().map(row -> {
                CartVO.Row r = new CartVO.Row();
                r.setCartId(row.getId());
                r.setItemId(row.getItemId());
                r.setQuantity(row.getQuantity());
                Item item = items.get(row.getItemId());
                if (item != null) {
                    r.setTitle(item.getTitle());
                    r.setCoverUrl(item.getCoverUrl());
                    r.setPrice(item.getPrice());
                    r.setStatus(item.getStatus());
                    r.setStatusText(ItemService.statusText(item.getStatus()));
                    r.setPreferPointId(item.getPreferPointId());
                    TradePoint p = item.getPreferPointId() == null ? null : points.get(item.getPreferPointId());
                    r.setPreferPointName(p == null ? null : p.getName());
                } else {
                    r.setStatus(4);
                    r.setStatusText("已失效");
                }
                return r;
            }).toList());
            result.add(group);
        });
        return result;
    }

    public List<CartItem> rawByIds(Long userId, List<Long> cartIds) {
        if (cartIds == null || cartIds.isEmpty()) {
            return List.of();
        }
        return cartMapper.selectList(Wrappers.<CartItem>lambdaQuery()
                .eq(CartItem::getUserId, userId).in(CartItem::getId, cartIds));
    }

    public void removeByIds(Collection<Long> cartIds) {
        if (cartIds != null && !cartIds.isEmpty()) {
            cartMapper.deleteByIds(cartIds);
        }
    }
}
