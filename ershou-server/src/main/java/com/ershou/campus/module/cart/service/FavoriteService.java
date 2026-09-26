package com.ershou.campus.module.cart.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.module.cart.entity.Favorite;
import com.ershou.campus.module.cart.mapper.FavoriteMapper;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.item.vo.ItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final ItemMapper itemMapper;
    private final ItemService itemService;
    private final CacheEvictor evictor;

    public void add(Long userId, Long itemId) {
        try {
            Favorite f = new Favorite();
            f.setUserId(userId);
            f.setItemId(itemId);
            favoriteMapper.insert(f);
            itemMapper.addFavCount(itemId, 1);
            // fav_count 在商品 VO 里：收藏数变了要删详情缓存（不影响地图的在售数）
            evictor.item(itemId);
            log.info("[favorite][add][{}][{}]", userId, itemId);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：重复收藏直接忽略
            log.debug("[favorite][add][{}][{}] 已收藏过", userId, itemId);
        }
    }

    public void remove(Long userId, Long itemId) {
        int deleted = favoriteMapper.delete(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId).eq(Favorite::getItemId, itemId));
        if (deleted > 0) {
            itemMapper.addFavCount(itemId, -1);
            evictor.item(itemId);
        }
    }

    public PageResult<ItemVO> page(Long userId, long pageNum, long pageSize) {
        Page<Favorite> page = favoriteMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<Favorite>lambdaQuery().eq(Favorite::getUserId, userId).orderByDesc(Favorite::getId));
        List<Long> itemIds = page.getRecords().stream().map(Favorite::getItemId).toList();
        if (itemIds.isEmpty()) {
            return PageResult.of(List.of(), page.getTotal(), page.getCurrent(), page.getSize());
        }
        Map<Long, Item> items = itemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        List<Item> ordered = itemIds.stream().map(items::get).filter(java.util.Objects::nonNull).toList();
        // 批量组装（分类 / 点位走字典缓存），这里是「我的收藏」列表，命中即已收藏
        List<ItemVO> vos = itemService.toVOList(ordered);
        vos.forEach(vo -> vo.setFavorited(true));
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public boolean isFavorited(Long userId, Long itemId) {
        if (userId == null) {
            return false;
        }
        return favoriteMapper.selectCount(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId).eq(Favorite::getItemId, itemId)) > 0;
    }
}
