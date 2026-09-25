package com.ershou.campus.module.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.ai.AuditDecision;
import com.ershou.campus.module.ai.AuditResult;
import com.ershou.campus.module.ai.ContentAuditService;
import com.ershou.campus.module.item.dto.ItemPublishDTO;
import com.ershou.campus.module.item.dto.ItemQueryDTO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.entity.ItemCategory;
import com.ershou.campus.module.item.mapper.ItemCategoryMapper;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.vo.ItemVO;
import com.ershou.campus.module.item.vo.SellerVO;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.service.TradePointService;
import com.ershou.campus.module.search.config.SearchProperties;
import com.ershou.campus.module.search.dto.ItemSearchHits;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.ershou.campus.module.search.service.ItemSearchService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    private final ItemMapper itemMapper;
    private final ItemCategoryMapper categoryMapper;
    private final UserService userService;
    private final TradePointService tradePointService;
    private final ContentAuditService auditService;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ItemSearchService itemSearchService;
    private final ItemIndexService itemIndexService;
    private final SearchProperties searchProperties;

    private static final int DEFAULT_HOURS_VALID = 60 * 24;

    public PageResult<ItemVO> page(ItemQueryDTO q) {
        // 关键词检索优先走 Elasticsearch；ES 不可用/异常时按配置降级 MySQL LIKE
        if (itemSearchService.supports(q)) {
            try {
                return searchViaEs(q);
            } catch (ItemSearchService.NotSuitableForEsException e) {
                // 深分页等场景：静默回落 MySQL，不视为 ES 故障
                log.debug("[item][search][es] {}，回落 MySQL LIKE", e.getMessage());
            } catch (Exception e) {
                itemSearchService.markFailure(e);
                if (!searchProperties.isFallbackToDb()) {
                    throw new BizException(ResultCode.SYSTEM_ERROR, "搜索服务暂时不可用，请稍后再试");
                }
                log.warn("[item][search][es] 检索失败，降级 MySQL LIKE：{}", e.getMessage());
            }
        } else if (searchProperties.isEnabled() && StringUtils.hasText(q.getKeyword())
                && !searchProperties.isFallbackToDb()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "搜索服务暂时不可用，请稍后再试");
        }
        return pageFromDb(q);
    }

    /** ES 命中 id → 回库取最新字段并按 ES 顺序组装；索引与库短暂不一致时以库为准 */
    private PageResult<ItemVO> searchViaEs(ItemQueryDTO q) {
        ItemSearchHits hits = itemSearchService.search(q);
        long pageSize = Math.min(q.getPageSize(), 100);
        if (hits.ids().isEmpty()) {
            return PageResult.of(List.<ItemVO>of(), hits.total(), q.getPageNum(), pageSize);
        }
        Map<Long, Item> items = itemMapper.selectBatchIds(hits.ids()).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<ItemVO> records = new ArrayList<>(hits.ids().size());
        for (Long id : hits.ids()) {
            Item item = items.get(id);
            if (item == null || item.getStatus() == null || item.getStatus() != 2) {
                continue;
            }
            ItemVO vo = toVO(item);
            ItemSearchHits.Highlight highlight = hits.highlights().get(id);
            if (highlight != null) {
                vo.setHighlightTitle(highlight.title());
                vo.setHighlightDescription(highlight.description());
            }
            records.add(vo);
        }
        return PageResult.of(records, hits.total(), q.getPageNum(), pageSize);
    }

    /** MySQL LIKE 兜底：无关键词的列表浏览，以及 ES 不可用时的搜索 */
    public PageResult<ItemVO> pageFromDb(ItemQueryDTO q) {
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 2);
        if (StringUtils.hasText(q.getKeyword())) {
            String kw = q.getKeyword().trim();
            w.and(x -> x.like(Item::getTitle, kw).or().like(Item::getDescription, kw));
        }
        if (q.getCategoryId() != null) {
            w.eq(Item::getCategoryId, q.getCategoryId());
        }
        if (q.getCampusId() != null) {
            w.eq(Item::getCampusId, q.getCampusId());
        }
        if (q.getSellerId() != null) {
            w.eq(Item::getSellerId, q.getSellerId());
        }
        switch (q.getSort() == null ? "latest" : q.getSort()) {
            case "price_asc" -> w.orderByAsc(Item::getPrice);
            case "price_desc" -> w.orderByDesc(Item::getPrice);
            default -> w.orderByDesc(Item::getPublishedAt).orderByDesc(Item::getId);
        }
        Page<Item> page = itemMapper.selectPage(new Page<>(q.getPageNum(), Math.min(q.getPageSize(), 100)), w);
        return PageResult.of(page, this::toVO);
    }

    public PageResult<ItemVO> mine(long pageNum, long pageSize, Long userId) {
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery()
                .eq(Item::getSellerId, userId)
                .orderByDesc(Item::getId);
        Page<Item> page = itemMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), w);
        return PageResult.of(page, this::toVO);
    }

    public ItemVO detail(Long id) {
        Item item = itemMapper.selectById(id);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        try {
            itemMapper.incrView(id);
        } catch (Exception e) {
            log.warn("浏览量自增失败: {}", e.getMessage());
        }
        ItemVO vo = toVO(item);
        vo.setDescription(item.getDescription());
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(ItemPublishDTO dto, Long userId) {
        SysUser user = userService.getById(userId);

        // 新号限额（注册 7 天内）：每日发布上限
        if (user.getCreatedAt() != null && user.getCreatedAt().isAfter(LocalDateTime.now().minusDays(7))) {
            String key = RedisKeys.publishLimit(userId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofDays(1));
            }
            int limit = user.getPublishLimit() == null ? 5 : user.getPublishLimit();
            if (count != null && count > limit) {
                throw new BizException(ResultCode.PUBLISH_LIMIT, "新账号每日最多发布 " + limit + " 件，明天再来吧");
            }
        }

        // AI 机审：禁售词直接拦截，可疑词转人工
        AuditResult audit = auditService.auditText(dto.getTitle() + " " + (dto.getDescription() == null ? "" : dto.getDescription()), "item");
        int status = 2;
        if (audit.decision() == AuditDecision.BLOCK) {
            throw new BizException(ResultCode.CONTENT_VIOLATION, audit.reason());
        }
        if (audit.decision() == AuditDecision.REVIEW) {
            status = 1;
        }

        Item item = new Item();
        item.setSellerId(userId);
        item.setSchoolId(user.getSchoolId());
        item.setCampusId(user.getCampusId());
        item.setTitle(dto.getTitle().trim());
        item.setDescription(dto.getDescription());
        item.setCategoryId(dto.getCategoryId());
        item.setConditionLevel(dto.getConditionLevel());
        item.setPrice(dto.getPrice());
        item.setOriginalPrice(dto.getOriginalPrice());
        item.setNegotiable(dto.getNegotiable() == null ? 1 : dto.getNegotiable());
        item.setStock(dto.getStock() == null ? 1 : dto.getStock());
        item.setIsUnique(dto.getIsUnique() == null ? 1 : dto.getIsUnique());
        item.setImages(writeJson(dto.getImages()));
        item.setCoverUrl(dto.getImages().get(0));
        item.setVideoUrl(dto.getVideoUrl());
        item.setTradeMode(1);
        item.setPreferPointId(dto.getPreferPointId());
        item.setStatus(status);
        item.setAuditResult(audit.decision() == AuditDecision.PASS ? 1 : null);
        item.setAuditReason(audit.reason());
        item.setExpireAt(LocalDateTime.now().plusMinutes(DEFAULT_HOURS_VALID));
        item.setPublishedAt(LocalDateTime.now());
        item.setViewCount(0);
        item.setChatCount(0);
        item.setFavCount(0);
        item.setVersion(0);
        itemMapper.insert(item);
        TxHooks.afterCommit(() -> itemIndexService.syncById(item.getId()));

        log.info("[item][publish][{}][{}] status={}", item.getId(), userId, status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", item.getId());
        data.put("status", status);
        data.put("statusText", statusText(status));
        return data;
    }

    @Transactional(rollbackFor = Exception.class)
    public void offline(Long itemId, Long userId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (!Objects.equals(item.getSellerId(), userId)) {
            throw new BizException(ResultCode.ITEM_NOT_OWNER);
        }
        if (item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD);
        }
        Item update = new Item();
        update.setId(itemId);
        update.setStatus(5);
        itemMapper.updateById(update);
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        log.info("[item][offline][{}][{}]", itemId, userId);
    }

    public Item getOnSale(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return item;
    }

    public ItemVO toVO(Item item) {
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
        vo.setStatus(item.getStatus());
        vo.setStatusText(statusText(item.getStatus()));
        vo.setStock(item.getStock());
        vo.setIsUnique(item.getIsUnique());
        vo.setViewCount(item.getViewCount());
        vo.setFavCount(item.getFavCount());
        vo.setPublishedAt(item.getPublishedAt());
        if (item.getCategoryId() != null) {
            ItemCategory c = categoryMapper.selectById(item.getCategoryId());
            vo.setCategoryName(c == null ? null : c.getName());
        }
        if (item.getPreferPointId() != null) {
            TradePoint p = tradePointService.getById(item.getPreferPointId());
            if (p != null) {
                vo.setPreferPointName(p.getName());
                vo.setPreferPointGuide(p.getGuideText());
            }
        }
        Map<Long, SysUser> sellers = userService.mapByIds(List.of(item.getSellerId()));
        SysUser seller = sellers.get(item.getSellerId());
        if (seller != null) {
            SellerVO sv = new SellerVO();
            sv.setId(seller.getId());
            sv.setNickname(seller.getNickname());
            sv.setAvatar(seller.getAvatar());
            sv.setCreditScore(seller.getCreditScore());
            sv.setIdentityFlag(seller.getIdentityFlag());
            sv.setShopName(seller.getShopName());
            vo.setSeller(sv);
        }
        return vo;
    }

    public static String statusText(Integer status) {
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

    public static String conditionText(Integer level) {
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

    private String writeJson(List<String> list) {
        try {
            return json.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readImages(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
