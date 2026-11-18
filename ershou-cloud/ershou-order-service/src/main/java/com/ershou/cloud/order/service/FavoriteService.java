package com.ershou.cloud.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.order.dto.ItemBriefVO;
import com.ershou.cloud.order.dto.SellerVO;
import com.ershou.cloud.order.entity.Favorite;
import com.ershou.cloud.order.feign.InnerItemVO;
import com.ershou.cloud.order.feign.InnerUserVO;
import com.ershou.cloud.order.feign.ItemClient;
import com.ershou.cloud.order.feign.UserClient;
import com.ershou.cloud.order.mapper.FavoriteMapper;
import com.ershou.cloud.order.util.Texts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收藏服务（第 103 轮从单体 {@code module.cart.service.FavoriteService} 迁入 order-service）。
 *
 * <p>数据：本服务自有库 {@code ershou_order.favorite}（唯一索引兜并发重复收藏）。
 * 商品信息走 item-service Feign；用户昵称/信用走 user-service Feign（降级留空）。
 *
 * <p><b>已知遗留</b>：旧单体收藏会同步 {@code item.fav_count} + 清商品详情缓存；全量拆分后 item 表属
 * item-service 库，order-service 不能跨库写，也不在本次 item-service 内部契约里 —— 因此本轮
 * fav_count 不再随收藏变化（前端卡片上的收藏数会滞后）。建议后续由 item-service 提供
 * {@code POST /inner/items/{id}/fav-count?delta=} 或由收藏事件走 MQ 异步累加。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final ItemClient itemClient;
    private final UserClient userClient;
    private final ObjectMapper objectMapper;

    public void add(Long userId, Long itemId) {
        // 校验商品存在（商品服务不可用时降级放行：收藏本身不依赖商品详情）
        try {
            Result<InnerItemVO> r = itemClient.get(itemId);
            if (r != null && r.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
                throw new BizException(ResultCode.ITEM_NOT_FOUND);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[favorite][add][{}][{}] 商品服务不可用，降级放行: {}", userId, itemId, e.getMessage());
        }
        try {
            Favorite f = new Favorite();
            f.setUserId(userId);
            f.setItemId(itemId);
            favoriteMapper.insert(f);
            log.info("[favorite][add][{}][{}]", userId, itemId);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：重复收藏直接忽略
            log.debug("[favorite][add][{}][{}] 已收藏过", userId, itemId);
        }
    }

    public void remove(Long userId, Long itemId) {
        favoriteMapper.delete(Wrappers.<Favorite>lambdaQuery()
                .eq(Favorite::getUserId, userId).eq(Favorite::getItemId, itemId));
    }

    /** 我的收藏分页（返回 ItemBriefVO：旧前端 ItemCard 需要的字段子集，JSON 兼容） */
    public PageResult<ItemBriefVO> page(Long userId, long pageNum, long pageSize) {
        Page<Favorite> page = favoriteMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<Favorite>lambdaQuery().eq(Favorite::getUserId, userId)
                        .orderByDesc(Favorite::getId));
        List<Long> itemIds = page.getRecords().stream().map(Favorite::getItemId).toList();
        if (itemIds.isEmpty()) {
            return PageResult.of(List.of(), page.getTotal(), page.getCurrent(), page.getSize());
        }
        Map<Long, InnerItemVO> items = batchItems(itemIds);
        Map<Long, InnerUserVO> sellers = batchUsers(items.values().stream()
                .map(InnerItemVO::getSellerId).filter(java.util.Objects::nonNull)
                .distinct().toList());
        List<ItemBriefVO> records = new ArrayList<>();
        for (Long itemId : itemIds) {
            InnerItemVO item = items.get(itemId);
            if (item == null) {
                // 商品服务不可用/商品已删除：占位保证收藏列表不丢条目（前端可点进详情页看真实状态）
                ItemBriefVO placeholder = new ItemBriefVO();
                placeholder.setId(itemId);
                placeholder.setTitle("商品信息加载失败（商品服务不可用或已删除）");
                placeholder.setStatusText("加载失败");
                placeholder.setFavorited(true);
                records.add(placeholder);
                continue;
            }
            records.add(toBrief(item, sellers.get(item.getSellerId())));
        }
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    // ---------------------------------------------------------------- 组装

    private ItemBriefVO toBrief(InnerItemVO item, InnerUserVO seller) {
        ItemBriefVO vo = new ItemBriefVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setDescription(item.getDescription());
        vo.setPrice(item.getPrice());
        vo.setOriginalPrice(item.getOriginalPrice());
        vo.setNegotiable(item.getNegotiable());
        vo.setConditionLevel(item.getConditionLevel());
        vo.setConditionText(Texts.condition(item.getConditionLevel()));
        vo.setCoverUrl(item.getCoverUrl());
        vo.setImages(parseImages(item.getImages()));
        vo.setCategoryId(item.getCategoryId());
        vo.setCampusId(item.getCampusId());
        vo.setPreferPointId(item.getPreferPointId());
        vo.setSellerType(item.getSellerType());
        vo.setStatus(item.getStatus());
        vo.setStatusText(Texts.itemStatus(item.getStatus()));
        vo.setStock(item.getStock());
        vo.setIsUnique(item.getIsUnique());
        vo.setViewCount(item.getViewCount());
        vo.setFavCount(item.getFavCount());
        vo.setPublishedAt(item.getPublishedAt());
        vo.setExpireAt(item.getExpireAt());
        vo.setFavorited(true);
        if (item.getSellerId() != null) {
            SellerVO s = new SellerVO();
            s.setId(item.getSellerId());
            if (seller != null) {
                s.setNickname(seller.getNickname());
                s.setAvatar(seller.getAvatar());
                s.setCreditScore(seller.getCreditScore());
                s.setIdentityFlag(seller.getIdentityFlag());
                s.setShopName(seller.getShopName());
            }
            vo.setSeller(s);
        }
        return vo;
    }

    /** item-service 内部接口把 images 作为 JSON 字符串返回，这里解析成数组（失败则 null，前端有兜底） */
    private List<String> parseImages(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("[favorite][images] 解析商品图片数组失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    private Map<Long, InnerItemVO> batchItems(List<Long> itemIds) {        Map<Long, InnerItemVO> map = new HashMap<>();
        try {
            Result<List<InnerItemVO>> r = itemClient.batch(itemIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
            if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                r.getData().forEach(i -> map.put(i.getId(), i));
                return map;
            }
        } catch (Exception e) {
            log.warn("[favorite][batch] 批量取商品失败，逐条降级: {}", e.getMessage());
        }
        for (Long id : itemIds) {
            try {
                Result<InnerItemVO> r = itemClient.get(id);
                if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                    map.put(id, r.getData());
                }
            } catch (Exception ignore) {
                // 商品服务不可用：占位
            }
        }
        return map;
    }

    private Map<Long, InnerUserVO> batchUsers(List<Long> userIds) {
        Map<Long, InnerUserVO> map = new HashMap<>();
        if (userIds.isEmpty()) {
            return map;
        }
        try {
            Result<List<InnerUserVO>> r = userClient.batch(userIds.stream().map(String::valueOf)
                    .collect(Collectors.joining(",")));
            if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                r.getData().forEach(u -> map.put(u.getId(), u));
                return map;
            }
        } catch (Exception e) {
            log.warn("[favorite][users] 批量取用户失败（昵称/信用降级为空）: {}", e.getMessage());
        }
        for (Long id : userIds) {
            try {
                Result<InnerUserVO> r = userClient.get(id);
                if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                    map.put(id, r.getData());
                }
            } catch (Exception ignore) {
                // 降级
            }
        }
        return map;
    }
}
