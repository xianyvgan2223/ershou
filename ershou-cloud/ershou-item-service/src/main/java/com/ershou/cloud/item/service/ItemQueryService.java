package com.ershou.cloud.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.item.config.ItemCacheKeys;
import com.ershou.cloud.item.config.ItemProperties;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.entity.ItemCategory;
import com.ershou.cloud.item.mapper.ItemCategoryMapper;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.vo.ItemVO;
import com.ershou.cloud.item.vo.SellerVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商品公开读（列表 + 详情），对应设计 4.3。
 *
 * <ul>
 *   <li>列表：MyBatis-Plus 分页，**只返回 status=2（在售）**；关键词/分类/排序下推到 SQL；</li>
 *   <li>详情：Redis **db2** {@code ershou:item:detail:{id}} 缓存（TTL 可配，默认 60s），
 *       浏览量 {@code INCR ershou:item:view:{id}}；</li>
 *   <li>所有 Redis 异常都降级为"直接回源"，不影响读接口可用性。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemQueryService {

    private static final TypeReference<ItemVO> ITEM_VO_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> IMAGES_TYPE = new TypeReference<>() {
    };

    private final ItemMapper itemMapper;
    private final ItemCategoryMapper categoryMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ItemProperties props;

    /** 商品列表：pageNum/pageSize/keyword/categoryId/sort，只返回在售(status=2) */
    public PageResult<ItemVO> page(long pageNum, long pageSize, String keyword, Long categoryId, String sort) {
        long num = Math.max(pageNum, 1);
        // pageSize 上限走 Nacos 动态配置 ershou.item.page-max（默认 100）
        long size = Math.min(Math.max(pageSize, 1), Math.max(props.getPageMax(), 1));
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 2);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(x -> x.like(Item::getTitle, kw).or().like(Item::getDescription, kw));
        }
        if (categoryId != null) {
            w.eq(Item::getCategoryId, categoryId);
        }
        switch (sort == null ? "latest" : sort) {
            case "price_asc" -> w.orderByAsc(Item::getPrice);
            case "price_desc" -> w.orderByDesc(Item::getPrice);
            default -> w.orderByDesc(Item::getPublishedAt).orderByDesc(Item::getId);
        }
        // 分页走 MyBatis-Plus 标准 selectPage（PaginationInnerInterceptor 由 MybatisPlusConfig 注册；
        // 3.5.9 该插件在 mybatis-plus-jsqlparser 包里，pom 已引）。
        Page<Item> page = itemMapper.selectPage(new Page<>(num, size), w);
        return PageResult.of(toVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 商品详情。不存在返回 null（由 Controller 转成 30001，与单体错误码一致）。
     * 详情**不做 status 过滤**：详情页要能展示已售出/已下架商品（与单体一致）。
     */
    public ItemVO detail(Long itemId) {
        ItemVO vo = readCache(itemId);
        if (vo == null) {
            Item item = itemMapper.selectById(itemId);
            if (item == null) {
                return null;
            }
            Map<Long, String> categoryNames = categoryNames(List.of(item));
            vo = toVO(item, categoryNames);
            writeCache(itemId, vo);
        }
        // 浏览量：Redis db2 增量（返回 pending 供"未回写"计数展示；DB view_count 作为基线只读）
        int pending = incrView(itemId);
        if (pending > 0) {
            vo.setViewCount((vo.getViewCount() == null ? 0 : vo.getViewCount()) + pending);
        }
        return vo;
    }

    // ------------------------------------------------------------ Redis（db2）

    private ItemVO readCache(Long itemId) {
        try {
            String cached = redis.opsForValue().get(ItemCacheKeys.itemDetail(itemId));
            if (cached == null) {
                return null;
            }
            return json.readValue(cached, ITEM_VO_TYPE);
        } catch (Exception e) {
            log.warn("[item][detail][{}] 读缓存失败，回源 DB：{}", itemId, e.getMessage());
            return null;
        }
    }

    private void writeCache(Long itemId, ItemVO vo) {
        try {
            int ttl = Math.max(props.getDetailCacheSeconds(), 1);
            redis.opsForValue().set(ItemCacheKeys.itemDetail(itemId), json.writeValueAsString(vo),
                    Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("[item][detail][{}] 写缓存失败（不影响本次返回）：{}", itemId, e.getMessage());
        }
    }

    private int incrView(Long itemId) {
        try {
            String key = ItemCacheKeys.itemView(itemId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofHours(2));
            }
            return count == null || count <= 0 ? 0 : count.intValue();
        } catch (Exception e) {
            log.warn("[item][view][{}] 浏览量计数失败，本次不计数：{}", itemId, e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------ 组装

    private List<ItemVO> toVOList(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNames = categoryNames(items);
        return items.stream().map(item -> toVO(item, categoryNames)).toList();
    }

    /** 分类名一次批量查回（列表 20 条只查 1 次，避免 N+1） */
    private Map<Long, String> categoryNames(List<Item> items) {
        List<Long> ids = items.stream().map(Item::getCategoryId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>(ids.size());
        for (ItemCategory category : categoryMapper.selectBatchIds(ids)) {
            if (category.getName() != null) {
                names.put(category.getId(), category.getName());
            }
        }
        return names;
    }

    private ItemVO toVO(Item item, Map<Long, String> categoryNames) {
        ItemVO vo = new ItemVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setPrice(item.getPrice());
        vo.setOriginalPrice(item.getOriginalPrice());
        vo.setNegotiable(item.getNegotiable());
        vo.setConditionLevel(item.getConditionLevel());
        vo.setConditionText(conditionText(item.getConditionLevel()));
        vo.setCoverUrl(item.getCoverUrl());
        vo.setImages(readImages(item.getImages()));
        vo.setCategoryId(item.getCategoryId());
        vo.setCampusId(item.getCampusId());
        vo.setPreferPointId(item.getPreferPointId());
        vo.setSellerType(item.getSellerType() == null ? 1 : item.getSellerType());
        vo.setPlatformPointId(item.getPlatformPointId());
        vo.setStatus(item.getStatus());
        vo.setStatusText(statusText(item.getStatus()));
        vo.setStock(item.getStock());
        vo.setIsUnique(item.getIsUnique());
        vo.setViewCount(item.getViewCount());
        vo.setFavCount(item.getFavCount());
        vo.setSaleCount(item.getSaleCount());
        vo.setPublishedAt(item.getPublishedAt());
        vo.setExpireAt(item.getExpireAt());
        if (item.getCategoryId() != null) {
            vo.setCategoryName(categoryNames.get(item.getCategoryId()));
        }
        // 跨库限制：只给 sellerId，昵称/头像由前端兜底或后续 Feign 补全（见 SellerVO 注释）
        if (item.getSellerId() != null) {
            SellerVO seller = new SellerVO();
            seller.setId(item.getSellerId());
            vo.setSeller(seller);
        }
        return vo;
    }

    private List<String> readImages(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return json.readValue(raw, IMAGES_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 状态文案（与单体 ItemService.statusText 完全一致，前端直接展示） */
    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 0 -> "草稿";
            case 1 -> "审核中";
            case 2 -> "在售";
            case 3 -> "已锁定";
            case 4 -> "已售出";
            case 5 -> "已下架";
            case 6 -> "审核未通过";
            case 7 -> "已过期";
            case 8 -> "违规下架";
            default -> "未知";
        };
    }

    /** 成色文案（与单体 ItemService.conditionText 一致） */
    private static String conditionText(Integer level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case 1 -> "全新未拆";
            case 2 -> "几乎全新";
            case 3 -> "轻微使用痕迹";
            case 4 -> "明显使用痕迹";
            case 5 -> "有瑕疵";
            default -> "其他";
        };
    }
}
