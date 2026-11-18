package com.ershou.cloud.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.item.dto.ItemPublishDTO;
import com.ershou.cloud.item.dto.ItemUpdateDTO;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.feign.InnerUserVO;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.review.ItemReviewService;
import com.ershou.cloud.item.search.ItemIndexService;
import com.ershou.cloud.item.support.RemoteService;
import com.ershou.cloud.item.support.TxHooks;
import com.ershou.cloud.item.support.cache.CacheEvictor;
import com.ershou.cloud.item.support.cache.DictionaryCache;
import com.ershou.cloud.item.vo.InnerItemVO;
import com.ershou.cloud.item.vo.ItemVO;
import com.ershou.cloud.item.vo.PointVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 内部接口业务（{@code /inner/items/**}，契约 §三 item-service + T102 追加）。
 *
 * <p>调用方：order-service（面交锁库/成交）、platform-service（平台商品增删改查）、
 * admin-service（管理端状态处置/统计）、map-service（点位商品/计数）。内部接口不经过网关，
 * 由各服务 OpenFeign 直连；本类只写 {@code ershou_item} 库，跨域通知走 {@link RemoteService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemInnerService {

    private final ItemMapper itemMapper;
    private final ItemService itemService;
    private final ItemReviewService itemReviewService;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;
    private final DictionaryCache dict;
    private final RemoteService remote;

    /** 平台官方卖家 id（platform-service 创建平台商品时使用） */
    @Value("${ershou.item.platform-seller-id:1000}")
    private Long platformSellerId;

    /** 平台商品有效期（天） */
    @Value("${ershou.item.platform-valid-days:30}")
    private int platformValidDays;

    // ------------------------------------------------------------------ 通用内部：详情/批量/统计

    public InnerItemVO detail(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return InnerItemVO.from(item);
    }

    /** 批量详情（order-service 列表补商品信息，避免 N+1）；按 ids 顺序返回，不存在的跳过 */
    public List<InnerItemVO> batch(Collection<Long> itemIds) {        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = itemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> map = new LinkedHashMap<>();
        for (Item item : itemMapper.selectBatchIds(ids)) {
            map.put(item.getId(), item);
        }
        List<InnerItemVO> result = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Item item = map.get(id);
            if (item != null) {
                result.add(InnerItemVO.from(item));
            }
        }
        return result;
    }

    /** 商品统计（admin-service 概览）：在售 / 待审 / 已下架(5,7,8) */
    public Map<String, Object> stats() {        Map<String, Object> result = new LinkedHashMap<>();
        result.put("onSale", countStatus(2));
        result.put("pendingReview", itemReviewService.pendingCount());
        result.put("offline", countStatus(5) + countStatus(7) + countStatus(8));
        result.put("total", itemMapper.selectCount(null));
        return result;
    }

    /**
     * 某卖家的在售商品数（T102 追加：user-service 公开主页统计用）。
     * 只统计在售(status=2)，与旧单体个人主页「在售 N 件」口径一致。
     */
    public Map<String, Object> countBySeller(Long sellerId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sellerId", sellerId);
        if (sellerId == null) {
            result.put("count", 0L);
            return result;
        }
        Long count = itemMapper.selectCount(Wrappers.<Item>lambdaQuery()
                .eq(Item::getSellerId, sellerId)
                .eq(Item::getStatus, 2));
        result.put("count", count == null ? 0L : count);
        return result;
    }

    private long countStatus(int status) {
        Long count = itemMapper.selectCount(Wrappers.<Item>lambdaQuery().eq(Item::getStatus, status));
        return count == null ? 0 : count;
    }

    /**
     * 管理端商品分页（admin-service「商品管理」页，Lead T102 最终补充）。
     *
     * <p>{@code GET /inner/items?status=&keyword=&sellerId=&page=&pageSize=}：
     * 任意状态可查（status 为空=全部），keyword 匹配标题/描述，sellerId 过滤；
     * 返回 {@link InnerItemVO}（含 description/images/categoryId/categoryName/campusName/
     * preferPointName/auditReason/rejectCode/viewCount/favCount/expireAt/createdAt）。
     * 分类名/点位名/卖家校区名经本库字典 + map/user-service 批量补齐（跨服务不可用降级为空）。
     */
    public PageResult<InnerItemVO> adminPage(Integer status, String keyword, Long sellerId,
                                             long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery();
        if (status != null) {
            w.eq(Item::getStatus, status);
        }
        if (sellerId != null) {
            w.eq(Item::getSellerId, sellerId);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(x -> x.like(Item::getTitle, kw).or().like(Item::getDescription, kw));
        }
        w.orderByDesc(Item::getUpdatedAt).orderByDesc(Item::getId);
        Page<Item> page = itemMapper.selectPage(new Page<>(num, size), w);
        List<Item> items = page.getRecords();
        List<InnerItemVO> records = items.stream().map(InnerItemVO::from).toList();
        enrich(records, items);
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 管理分页/审核详情的展示字段补齐：分类名（本库）、点位名（map）、卖家校区名（user） */
    private void enrich(List<InnerItemVO> vos, List<Item> items) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        Map<Long, String> categoryNames = dict.categoryNames(
                items.stream().map(Item::getCategoryId).filter(Objects::nonNull).toList());
        Map<Long, PointVO> points = remote.pointsForItems(items);
        Map<Long, InnerUserVO> sellers = remote.users(
                items.stream().map(Item::getSellerId).filter(Objects::nonNull).collect(Collectors.toSet()));
        for (InnerItemVO vo : vos) {
            if (vo.getCategoryId() != null) {
                vo.setCategoryName(categoryNames.get(vo.getCategoryId()));
            }
            if (vo.getPreferPointId() != null) {
                PointVO p = points.get(vo.getPreferPointId());
                if (p != null) {
                    vo.setPreferPointName(p.getName());
                }
            }
            InnerUserVO seller = vo.getSellerId() == null ? null : sellers.get(vo.getSellerId());
            if (seller != null) {
                vo.setCampusName(seller.getCampusName());
            }
        }
    }

    // ------------------------------------------------------------------ 点位商品（map-service）

    /**
     * 某点位/校区下的在售商品分页（地图小窗）。
     * pointId 为空时返回该校区全部在售（带点位聚合计数由 count-by-point 提供）。
     */
    public PageResult<InnerItemVO> byPoint(Long campusId, Long pointId, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 2);
        if (campusId != null) {
            w.eq(Item::getCampusId, campusId);
        }
        if (pointId != null) {
            w.eq(Item::getPreferPointId, pointId);
        }
        w.orderByDesc(Item::getPublishedAt).orderByDesc(Item::getId);
        Page<Item> page = itemMapper.selectPage(new Page<>(num, size), w);
        List<InnerItemVO> records = page.getRecords().stream().map(InnerItemVO::from).toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 校区内各点位的在售商品数（地图气泡数字）：[{pointId,count}] */
    public List<Map<String, Object>> countByPoint(Long campusId) {
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery()
                .eq(Item::getStatus, 2)
                .isNotNull(Item::getPreferPointId)
                .select(Item::getPreferPointId);
        if (campusId != null) {
            w.eq(Item::getCampusId, campusId);
        }
        List<Item> items = itemMapper.selectList(w);
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Item item : items) {
            counts.merge(item.getPreferPointId(), 1L, Long::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>(counts.size());
        counts.forEach((pointId, count) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pointId", pointId);
            row.put("count", count);
            result.add(row);
        });
        return result;
    }

    // ------------------------------------------------------------------ 管理端状态处置（admin-service）

    /**
     * 管理端修改商品状态（契约：{@code POST /inner/items/{id}/status?status=}）。
     *
     * <p>目标状态语义：2=审核通过/恢复在售；6=审核驳回；5=下架；8=违规强制下架。
     * 全部使用条件更新兜并发（0 行即状态已变化 → REVIEW_ALREADY_HANDLED）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateStatus(Long itemId, Integer target, String reason, String rejectCode, Long operatorId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (target == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少目标状态 status");
        }
        Item update = new Item();
        update.setId(itemId);
        int rows;
        switch (target) {
            case 2 -> {
                if (item.getStatus() != null && item.getStatus() == 1) {
                    rows = itemMapper.approveAudit(itemId, StringUtils.hasText(reason) ? reason : "审核通过");
                } else {
                    // 从已下架/已过期恢复在售（运营手动上架）
                    update.setStatus(2);
                    rows = itemMapper.update(update, Wrappers.<Item>lambdaUpdate()
                            .eq(Item::getId, itemId).in(Item::getStatus, 5, 7));
                }
            }
            case 6 -> rows = itemMapper.rejectAudit(itemId,
                    StringUtils.hasText(reason) ? reason : "审核未通过", rejectCode);
            case 5 -> {
                int closed = remote.closeActiveOrders(itemId, "商品已被平台下架，本次约面交自动作废");
                log.info("[inner][status][{}] 管理端下架，作废 {} 张挂单", itemId, closed);
                rows = itemMapper.downlist(itemId);
            }
            case 8 -> {
                int closed = remote.closeActiveOrders(itemId, "商品已被平台强制下架，本次约面交自动作废");
                log.info("[inner][status][{}] 违规强制下架，作废 {} 张挂单", itemId, closed);
                rows = itemMapper.forceOffline(itemId,
                        StringUtils.hasText(reason) ? reason : "违规下架");
            }
            default -> throw new BizException(ResultCode.PARAM_ERROR, "不支持的目标状态：" + target);
        }
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_ALREADY_HANDLED);
        }
        afterItemWrite(itemId);
        log.info("[inner][status][{}][operator={}] {} → {} reason={}", itemId, operatorId,
                item.getStatus(), target, reason);
        return rows;
    }

    // ------------------------------------------------------------------ 平台商品（platform-service）

    /** 平台商品分页：seller_type=2；status 为空=全部状态；支持分类筛选与价格/时间排序（T106 platform-service 需求） */
    public PageResult<ItemVO> platformPage(Long campusId, Long pointId, Integer status, String keyword,
                                           Long categoryId, String sort, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery().eq(Item::getSellerType, 2);
        if (campusId != null) {
            w.eq(Item::getCampusId, campusId);
        }
        if (pointId != null) {
            w.eq(Item::getPlatformPointId, pointId);
        }
        if (status != null) {
            w.eq(Item::getStatus, status);
        }
        if (categoryId != null) {
            w.eq(Item::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(x -> x.like(Item::getTitle, kw).or().like(Item::getDescription, kw));
        }
        switch (sort == null ? "latest" : sort) {
            case "price_asc" -> w.orderByAsc(Item::getPrice);
            case "price_desc" -> w.orderByDesc(Item::getPrice);
            default -> w.orderByDesc(Item::getUpdatedAt).orderByDesc(Item::getId);
        }
        Page<Item> page = itemMapper.selectPage(new Page<>(num, size), w);
        return PageResult.of(itemService.toVOList(page.getRecords()), page.getTotal(),
                page.getCurrent(), page.getSize());
    }

    public ItemVO platformDetail(Long itemId) {
        Item item = requirePlatformItem(itemId);
        return itemService.toVO(item);
    }

    /** 平台商品编辑（内部调用，不做卖家属主校验，但必须是平台商品） */
    @Transactional(rollbackFor = Exception.class)
    public ItemVO platformUpdate(Long itemId, ItemUpdateDTO dto) {
        Item item = requirePlatformItem(itemId);
        Item update = new Item();
        update.setId(itemId);
        if (StringUtils.hasText(dto.getTitle())) {
            update.setTitle(dto.getTitle().trim());
        }
        if (dto.getDescription() != null) {
            update.setDescription(dto.getDescription());
        }
        if (dto.getCategoryId() != null) {
            update.setCategoryId(dto.getCategoryId());
        }
        if (dto.getConditionLevel() != null) {
            update.setConditionLevel(dto.getConditionLevel());
        }
        if (dto.getPrice() != null) {
            update.setPrice(dto.getPrice());
        }
        if (dto.getOriginalPrice() != null) {
            update.setOriginalPrice(dto.getOriginalPrice());
        }
        if (dto.getStock() != null) {
            update.setStock(dto.getStock());
        }
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            try {
                update.setImages(itemService.writeJsonPublic(dto.getImages()));
            } catch (Exception e) {
                log.warn("[inner][platform-update][{}] 图片序列化失败：{}", itemId, e.getMessage());
            }
            update.setCoverUrl(dto.getImages().get(0));
        }
        if (dto.getPreferPointId() != null) {
            update.setPreferPointId(dto.getPreferPointId());
        }
        // T106：运营台编辑平台商品时改代卖点
        if (dto.getPlatformPointId() != null) {
            update.setPlatformPointId(dto.getPlatformPointId());
        }
        if (dto.getVideoUrl() != null) {
            update.setVideoUrl(dto.getVideoUrl());
        }
        itemMapper.updateById(update);
        afterItemWrite(itemId);
        log.info("[inner][platform-update][{}] 平台商品已更新（原 sellerType={}）", itemId, item.getSellerType());
        return itemService.toVO(itemMapper.selectById(itemId));
    }

    /**
     * 创建平台商品：seller_id=平台官方账号、seller_type=2、免审核直接在售、有效期 platform-valid-days 天。
     * 平台商品只落 ershou_item.item，platform-service 不直接持有 item 表。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> platformCreate(ItemPublishDTO dto, Long operatorId) {
        // 契约：platform_point_id 必填（平台商品必须挂在代卖/自提点上）
        if (dto.getPlatformPointId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "平台商品必须指定代卖点 platformPointId");
        }
        Item item = new Item();
        item.setSellerId(platformSellerId);
        item.setSellerType(2);
        item.setPlatformPointId(dto.getPlatformPointId());
        // campusId：优先取 DTO（platform-service 传入）；否则用代卖点经 map-service 反查校区（不可用则 null 降级）
        Long campusId = dto.getCampusId();
        if (campusId == null) {
            var point = remote.point(dto.getPlatformPointId());
            if (point != null) {
                campusId = point.getCampusId();
            }
        }
        item.setCampusId(campusId);
        item.setTitle(dto.getTitle().trim());
        item.setDescription(dto.getDescription());
        item.setCategoryId(dto.getCategoryId());
        item.setConditionLevel(dto.getConditionLevel());
        item.setPrice(dto.getPrice());
        item.setOriginalPrice(dto.getOriginalPrice());
        item.setNegotiable(dto.getNegotiable() == null ? 0 : dto.getNegotiable());
        item.setStock(dto.getStock() == null ? 1 : dto.getStock());
        item.setIsUnique(dto.getIsUnique() == null ? 0 : dto.getIsUnique());
        item.setImages(itemService.writeJsonPublic(dto.getImages()));
        item.setCoverUrl(dto.getImages() == null || dto.getImages().isEmpty() ? null : dto.getImages().get(0));
        item.setVideoUrl(dto.getVideoUrl());
        item.setTradeMode(1);
        item.setPreferPointId(dto.getPreferPointId());
        item.setStatus(2);
        item.setAuditResult(1);
        item.setAuditReason("平台自营商品免机审");
        item.setExpireAt(LocalDateTime.now().plusDays(Math.max(platformValidDays, 1)));
        item.setPublishedAt(LocalDateTime.now());
        item.setViewCount(0);
        item.setChatCount(0);
        item.setFavCount(0);
        item.setSaleCount(0);
        item.setVersion(0);
        item.setLastLiveTitle(item.getTitle());
        item.setLastLiveDescHash(ItemReviewService.hash(item.getDescription()));
        itemMapper.insert(item);
        afterItemWrite(item.getId());
        log.info("[inner][platform-create][{}][operator={}] 平台商品已创建 pointId={}",
                item.getId(), operatorId, dto.getPlatformPointId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", item.getId());
        data.put("status", 2);
        data.put("statusText", ItemService.statusText(2));
        data.put("sellerType", 2);
        return data;
    }

    /** 平台商品下架：→ 已下架(5)，并作废挂单 */
    @Transactional(rollbackFor = Exception.class)
    public void platformOffline(Long itemId, String reason) {
        requirePlatformItem(itemId);
        int rows = itemMapper.downlist(itemId);
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_NOT_ACTIONABLE, "该平台商品已不在可处置状态");
        }
        int closed = remote.closeActiveOrders(itemId, "平台商品已下架，本次约面交自动作废");
        afterItemWrite(itemId);
        log.info("[inner][platform-offline][{}] 平台商品下架，作废 {} 张挂单；原因：{}", itemId, closed, reason);
    }

    /** 平台商品重新上架：5/7 → 2 */
    @Transactional(rollbackFor = Exception.class)
    public void platformOnline(Long itemId) {
        requirePlatformItem(itemId);
        Item update = new Item();
        update.setStatus(2);
        update.setPublishedAt(LocalDateTime.now());
        int rows = itemMapper.update(update, Wrappers.<Item>lambdaUpdate()
                .eq(Item::getId, itemId)
                .eq(Item::getSellerType, 2)
                .in(Item::getStatus, 5, 7));
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_NOT_ACTIONABLE, "该平台商品当前状态不允许上架");
        }
        afterItemWrite(itemId);
        log.info("[inner][platform-online][{}] 平台商品已重新上架", itemId);
    }

    /**
     * 平台数据概览：商品数 / 库存 / GMV。
     *
     * <p>GMV 需要订单成交数据（ershou_order 库），item-service 不跨库统计 → 当前返回 null 并在
     * {@code note} 说明，由 platform-service / admin-service 用 order-service 数据自行补（契约备注允许）。
     */
    public Map<String, Object> platformSummary(Long campusId) {
        LambdaQueryWrapper<Item> base = Wrappers.<Item>lambdaQuery().eq(Item::getSellerType, 2);
        if (campusId != null) {
            base.eq(Item::getCampusId, campusId);
        }
        List<Item> items = itemMapper.selectList(base);
        long onSale = items.stream().filter(i -> i.getStatus() != null && i.getStatus() == 2).count();
        long totalStock = items.stream()
                .filter(i -> i.getStatus() != null && i.getStatus() == 2)
                .map(Item::getStock).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        long soldCount = items.stream().map(Item::getSaleCount).filter(Objects::nonNull)
                .mapToLong(Integer::longValue).sum();
        // T106：库存告警（在售且 stock<=2）
        long lowStockCount = items.stream()
                .filter(i -> i.getStatus() != null && i.getStatus() == 2)
                .map(Item::getStock).filter(Objects::nonNull)
                .filter(s -> s <= 2).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("campusId", campusId);
        result.put("platformItemCount", items.size());
        result.put("onSaleCount", onSale);
        result.put("totalStock", totalStock);
        result.put("soldCount", soldCount);
        result.put("lowStockCount", lowStockCount);
        result.put("gmv", null);
        result.put("note", "GMV 需订单成交数据（ershou_order），item-service 不跨库统计；请由 platform/admin 用 order-service 汇总");
        return result;
    }

    // ------------------------------------------------------------------ 内部工具

    private Item requirePlatformItem(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (item.getSellerType() == null || item.getSellerType() != 2) {
            throw new BizException(ResultCode.PARAM_ERROR, "该商品不是平台商品（seller_type≠2）");
        }
        return item;
    }

    /** 写后统一收尾：ES 同步 + 详情缓存失效 */
    private void afterItemWrite(Long itemId) {
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
    }
}
