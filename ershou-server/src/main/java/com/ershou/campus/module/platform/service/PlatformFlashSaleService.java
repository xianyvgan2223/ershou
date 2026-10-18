package com.ershou.campus.module.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.platform.config.PlatformCacheKeys;
import com.ershou.campus.module.platform.dto.FlashSaleItemSaveDTO;
import com.ershou.campus.module.platform.dto.FlashSaleSaveDTO;
import com.ershou.campus.module.platform.entity.PlatformFlashSale;
import com.ershou.campus.module.platform.entity.PlatformFlashSaleItem;
import com.ershou.campus.module.platform.entity.PlatformItemRow;
import com.ershou.campus.module.platform.entity.PlatformSeckillRecord;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleItemMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleMapper;
import com.ershou.campus.module.platform.mapper.PlatformItemMapper;
import com.ershou.campus.module.platform.mapper.PlatformSeckillRecordMapper;
import com.ershou.campus.module.platform.util.PlatformTimeUtils;
import com.ershou.campus.module.platform.vo.AdminFlashSaleItemVO;
import com.ershou.campus.module.platform.vo.AdminFlashSaleVO;
import com.ershou.campus.module.platform.vo.FlashSaleItemVO;
import com.ershou.campus.module.platform.vo.FlashSaleVO;
import com.ershou.campus.module.platform.vo.GrabVO;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台秒杀服务（设计 3.2 场次读取 / 3.3 抢购）。
 *
 * <p>抢购链路（设计第四节）：限流 → Lua 原子扣减 Redis 库存与限购 Hash →
 * {@code OrderService#platformBuy} 建平台单 → 落 {@code platform_seckill_record} 并累加 sold_count。
 * <b>建单失败必须补偿回滚 Redis（INCR + HINCRBY -1）</b>，否则用户没抢到却少了一份库存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformFlashSaleService {

    /** 场次缓存 TTL：设计 30s，取 27s 给 JsonCache 的 +0~10% 抖动留余量（实际 TTL 不超过 30s） */
    private static final Duration FLASH_TTL = Duration.ofSeconds(27);
    private static final TypeReference<FlashSaleVO> FLASH_TYPE = new TypeReference<>() {
    };
    private static final int STATUS_PREVIEW = 1;
    private static final int STATUS_RUNNING = 2;
    private static final int STATUS_ENDED = 3;
    /** item.status=2 在售（与个人商品同一套状态码） */
    private static final int ITEM_STATUS_ON_SALE = 2;

    private static final String MSG_GRAB_SUCCESS = "抢购成功";
    private static final String MSG_SOLD_OUT = "手慢了，这件商品已被抢光";
    private static final String MSG_NOT_WARMED = "活动尚未预热，请稍后重试";
    private static final String MSG_RATE_LIMITED = "操作过于频繁，请 5 秒后再试";
    private static final String MSG_EXPIRED = "秒杀未开始或已结束";

    private final PlatformFlashSaleMapper flashSaleMapper;
    private final PlatformFlashSaleItemMapper flashSaleItemMapper;
    private final PlatformSeckillRecordMapper seckillRecordMapper;
    private final PlatformItemMapper itemMapper;
    private final PlatformCacheService cacheService;
    private final JsonCache jsonCache;
    private final OrderService orderService;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;

    /** 默认校区（设计：campusId 缺省按 2 处理） */
    private static final long DEFAULT_CAMPUS_ID = 2L;

    // ---------------------------------------------------------------- 公开读

    /**
     * 当前进行中的秒杀场次（设计 3.2）：无场次返回 null。
     *
     * <p>场次与商品信息走 30s 缓存；倒计时、剩余库存、是否已抢是"每次都变"的动态字段，
     * 在缓存读取后实时重算/覆盖，避免用户看到 30 秒前的旧库存。
     */
    public FlashSaleVO current(Long campusId, Long userId) {
        Long keyCampus = campusId == null ? 0L : campusId;
        FlashSaleVO vo = jsonCache.getOrLoad(PlatformCacheKeys.flash(keyCampus), FLASH_TTL, FLASH_TYPE,
                () -> loadCurrent(campusId));
        if (vo == null || vo.getEndAt() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(vo.getEndAt())) {
            return null;
        }
        vo.setStatus(now.isBefore(vo.getStartAt()) ? STATUS_PREVIEW : STATUS_RUNNING);
        vo.setRemainSeconds(Math.max(Duration.between(now, vo.getEndAt()).getSeconds(), 0L));
        List<FlashSaleItemVO> items = vo.getItems() == null ? List.of() : vo.getItems();
        Set<Long> grabbedIds = userId == null ? Set.of()
                : grabbedItemIds(userId, items.stream().map(FlashSaleItemVO::getItemId).toList());
        for (FlashSaleItemVO item : items) {
            int left = cacheService.seckillLeftStock(item.getItemId());
            if (left < 0) {
                int stock = item.getStock() == null ? 0 : item.getStock();
                int sold = item.getSoldCount() == null ? 0 : item.getSoldCount();
                left = Math.max(stock - sold, 0);
            }
            item.setLeftStock(left);
            item.setGrabbed(grabbedIds.contains(item.getItemId()));
        }
        return vo;
    }

    /** 当前进行中场次（内部用，概览接口取场次标题/状态） */
    public PlatformFlashSale currentScene(Long campusId) {
        return flashSaleMapper.selectCurrent(campusId);
    }

    // ---------------------------------------------------------------- 抢购

    /** 抢购（设计 3.3）：1 成功 / 0 抢完 / -1 未预热 / -2 超限 / -3 未开始或已结束 / -4 限流 */
    public GrabVO grab(Long itemId, Long userId) {
        if (itemId == null || userId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少商品或用户");
        }
        // 1) 必须在"当前进行中场次"里，且商品仍在售、有库存
        PlatformFlashSaleItem saleItem = flashSaleItemMapper.selectCurrentByItemId(itemId);
        if (saleItem == null) {
            return GrabVO.of(PlatformCacheService.CODE_EXPIRED, MSG_EXPIRED);
        }
        int limit = saleItem.getPerUserLimit() == null || saleItem.getPerUserLimit() < 1
                ? 1 : saleItem.getPerUserLimit();
        // 2) 已经抢过：直接按"超限"返回，不消耗限流次数。
        //    顺序原因：验收口径要求"同一用户再抢 => code=-2 超限"；若先限流，5 秒内重复点会先被 -4 拦下
        //    （QA A6-18 / A6.1 同用户重复抢购）。DB 抢购记录唯一键是权威判据。
        if (alreadyGrabbed(itemId, userId)) {
            return GrabVO.of(PlatformCacheService.CODE_LIMIT, "每人限购 " + limit + " 件");
        }
        PlatformItemRow item = itemMapper.selectPlatformItem(itemId);
        if (item == null || item.getStatus() == null || item.getStatus() != ITEM_STATUS_ON_SALE
                || item.getStock() == null || item.getStock() < 1) {
            return GrabVO.of(PlatformCacheService.CODE_SOLD_OUT, MSG_SOLD_OUT);
        }
        // 3) 限流：5 秒 1 次
        if (!cacheService.tryRateLimit(PlatformCacheKeys.rateGrab(userId))) {
            return GrabVO.of(PlatformCacheService.CODE_RATE_LIMITED, MSG_RATE_LIMITED);
        }

        // 3) Lua 原子扣减
        long result = cacheService.grabSeckill(itemId, userId, limit);
        if (result == PlatformCacheService.CODE_NOT_WARMED) {
            return GrabVO.of(PlatformCacheService.CODE_NOT_WARMED, MSG_NOT_WARMED);
        }
        if (result == PlatformCacheService.CODE_SOLD_OUT) {
            return GrabVO.of(PlatformCacheService.CODE_SOLD_OUT, MSG_SOLD_OUT);
        }
        if (result == PlatformCacheService.CODE_LIMIT) {
            return GrabVO.of(PlatformCacheService.CODE_LIMIT, "每人限购 " + limit + " 件");
        }

        // 4) 建平台单（内部条件更新防超卖）。失败必须补偿 Redis，用户不能"没抢到还占名额"
        Map<String, Object> order;
        try {
            order = orderService.platformBuy(itemId, userId, 1, saleItem.getSeckillPrice(),
                    item.getPlatformPointId());
        } catch (BizException e) {
            cacheService.rollbackSeckill(itemId, userId);
            log.warn("[platform][grab] 建单失败已回滚 Redis item={} user={}：{}", itemId, userId, e.getMessage());
            return GrabVO.of(PlatformCacheService.CODE_SOLD_OUT,
                    e.getMessage() == null ? MSG_SOLD_OUT : e.getMessage());
        } catch (Exception e) {
            cacheService.rollbackSeckill(itemId, userId);
            log.error("[platform][grab] 建单异常已回滚 Redis item={} user={}", itemId, userId, e);
            return GrabVO.of(PlatformCacheService.CODE_SOLD_OUT, "抢购失败，名额已释放，请稍后重试");
        }

        Long orderId = asLong(order.get("orderId"));
        String orderNo = order.get("orderNo") == null ? null : String.valueOf(order.get("orderNo"));
        // 5) 落抢购成功记录（DB 真值，预热/已抢判断都靠它）；唯一键兜底重复
        try {
            PlatformSeckillRecord record = new PlatformSeckillRecord();
            record.setItemId(itemId);
            record.setUserId(userId);
            record.setOrderId(orderId);
            record.setPrice(saleItem.getSeckillPrice());
            record.setCreatedAt(LocalDateTime.now());
            seckillRecordMapper.insert(record);
            flashSaleItemMapper.incrementSoldCount(saleItem.getId());
        } catch (DuplicateKeyException e) {
            // 极端竞态：Lua 已拦住同一用户重复抢，这里只记日志，订单已生成不能回滚用户已得到的单
            log.warn("[platform][grab] 抢购记录唯一键已存在 item={} user={} order={}", itemId, userId, orderNo);
        } catch (Exception e) {
            log.warn("[platform][grab] 写抢购记录失败（订单已生成，预热按记录重算会略有偏差）item={} user={}：{}",
                    itemId, userId, e.getMessage());
        }
        log.info("[platform][grab] 抢购成功 item={} user={} orderNo={} price={}",
                itemId, userId, orderNo, saleItem.getSeckillPrice());
        return new GrabVO(true, PlatformCacheService.CODE_SUCCESS, MSG_GRAB_SUCCESS, orderNo, orderId);
    }

    // ================================================================
    // 第 3 轮 · 运营台秒杀场次管理（设计 3.4 / 3.8）
    // ================================================================

    /** 运营台场次列表：该校区全部场次（含预告/结束），带商品明细与实时剩余 */
    public List<AdminFlashSaleVO> adminList(Long campusId) {
        Long effective = campusId == null ? DEFAULT_CAMPUS_ID : campusId;
        List<PlatformFlashSale> scenes = flashSaleMapper.selectList(Wrappers.<PlatformFlashSale>lambdaQuery()
                .eq(PlatformFlashSale::getCampusId, effective)
                .orderByDesc(PlatformFlashSale::getId));
        List<AdminFlashSaleVO> result = new ArrayList<>(scenes.size());
        for (PlatformFlashSale scene : scenes) {
            result.add(toAdminVO(scene));
        }
        return result;
    }

    /** 新建场次：写 DB 后失效场次缓存 + 只重建该场次 Redis（不整库重建） */
    @Transactional(rollbackFor = Exception.class)
    public AdminFlashSaleVO create(FlashSaleSaveDTO dto) {
        PlatformFlashSale scene = new PlatformFlashSale();
        applySceneFields(scene, dto, true);
        flashSaleMapper.insert(scene);
        Long id = scene.getId();
        Long campusId = scene.getCampusId();
        TxHooks.afterCommit(() -> {
            cacheService.evictFlashList(campusId);
            cacheService.rebuildFlashSale(id);
        });
        log.info("[platform][flash][create][{}] campus={} title={} status={}",
                id, campusId, scene.getTitle(), scene.getStatus());
        return toAdminVO(flashSaleMapper.selectById(id));
    }

    /** 编辑场次（时间/状态/排序）：保存后失效场次缓存 + 只重建该场次 Redis */
    @Transactional(rollbackFor = Exception.class)
    public AdminFlashSaleVO update(Long id, FlashSaleSaveDTO dto) {
        PlatformFlashSale existing = flashSaleMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "秒杀场次不存在");
        }
        Long oldCampusId = existing.getCampusId();
        PlatformFlashSale update = new PlatformFlashSale();
        update.setId(id);
        applySceneFields(update, dto, false);
        flashSaleMapper.updateById(update);
        Long campusId = update.getCampusId() != null ? update.getCampusId() : oldCampusId;
        TxHooks.afterCommit(() -> {
            cacheService.evictFlashList(campusId);
            if (!Objects.equals(oldCampusId, campusId)) {
                cacheService.evictFlashList(oldCampusId);
            }
            cacheService.rebuildFlashSale(id);
        });
        log.info("[platform][flash][update][{}] campus={} title={} status={}",
                id, campusId, update.getTitle(), update.getStatus());
        return toAdminVO(flashSaleMapper.selectById(id));
    }

    /**
     * 场次添加商品：校验是平台商品、不重复；保存后失效场次缓存 + 只重建该场次 Redis。
     *
     * <p>同时保证 {@code item.stock >= seckill_stock}（与第 2 轮迁移第 6 节同口径），
     * 否则多份秒杀会在第 1 单后因 DB 库存归零退化成"只能卖 1 份"。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminFlashSaleVO addItem(Long sceneId, FlashSaleItemSaveDTO dto) {
        PlatformFlashSale scene = flashSaleMapper.selectById(sceneId);
        if (scene == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "秒杀场次不存在");
        }
        PlatformItemRow item = itemMapper.selectPlatformItem(dto.getItemId());
        if (item == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "只能添加平台商品（seller_type=2）");
        }
        Long duplicated = flashSaleItemMapper.selectCount(Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                .eq(PlatformFlashSaleItem::getFlashSaleId, sceneId)
                .eq(PlatformFlashSaleItem::getItemId, dto.getItemId()));
        if (duplicated != null && duplicated > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "该商品已在场次中，请直接编辑或先移除");
        }
        int stock = dto.getSeckillStock();
        PlatformFlashSaleItem entity = new PlatformFlashSaleItem();
        entity.setFlashSaleId(sceneId);
        entity.setItemId(dto.getItemId());
        entity.setSeckillPrice(dto.getSeckillPrice());
        entity.setSeckillStock(stock);
        entity.setSoldCount(0);
        entity.setPerUserLimit(dto.getPerUserLimit() == null || dto.getPerUserLimit() < 1
                ? 1 : dto.getPerUserLimit());
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
        flashSaleItemMapper.insert(entity);

        int itemStock = item.getStock() == null ? 0 : item.getStock();
        if (itemStock < stock) {
            itemMapper.raiseStockForSeckill(dto.getItemId(), stock);
            Long itemId = dto.getItemId();
            TxHooks.afterCommit(() -> {
                itemIndexService.syncById(itemId);
                evictor.itemChanged(itemId);
            });
            log.info("[platform][flash][add-item] item={} stock {} -> {}（抬高到秒杀库存）",
                    itemId, itemStock, stock);
        }
        Long campusId = scene.getCampusId();
        TxHooks.afterCommit(() -> {
            cacheService.evictFlashList(campusId);
            cacheService.rebuildFlashSale(sceneId);
        });
        return toAdminVO(flashSaleMapper.selectById(sceneId));
    }

    /** 场次移除商品：删 DB 行 + 清该商品 Redis 库存 key + 只重建该场次 */
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long sceneId, Long itemId) {
        PlatformFlashSale scene = flashSaleMapper.selectById(sceneId);
        if (scene == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "秒杀场次不存在");
        }
        int rows = flashSaleItemMapper.delete(Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                .eq(PlatformFlashSaleItem::getFlashSaleId, sceneId)
                .eq(PlatformFlashSaleItem::getItemId, itemId));
        if (rows == 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "场次里没有该商品");
        }
        Long campusId = scene.getCampusId();
        TxHooks.afterCommit(() -> {
            cacheService.evictSeckillItem(itemId);
            cacheService.evictFlashList(campusId);
            cacheService.rebuildFlashSale(sceneId);
        });
        log.info("[platform][flash][remove-item] scene={} item={}", sceneId, itemId);
    }

    /** 场次切状态（1 预告 / 2 进行中 / 3 结束）：保存后失效场次缓存 + 只重建该场次 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long sceneId, Integer status) {
        if (status == null || (status != STATUS_PREVIEW && status != STATUS_RUNNING && status != STATUS_ENDED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "场次状态只能是 1（预告）/ 2（进行中）/ 3（已结束）");
        }
        PlatformFlashSale existing = flashSaleMapper.selectById(sceneId);
        if (existing == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "秒杀场次不存在");
        }
        PlatformFlashSale update = new PlatformFlashSale();
        update.setId(sceneId);
        update.setStatus(status);
        flashSaleMapper.updateById(update);
        Long campusId = existing.getCampusId();
        TxHooks.afterCommit(() -> {
            cacheService.evictFlashList(campusId);
            cacheService.rebuildFlashSale(sceneId);
        });
        log.info("[platform][flash][status][{}] {} -> {}", sceneId, existing.getStatus(), status);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 运营台场次视图：场次 + 商品明细 + 实时剩余（Redis，未预热回退 seckill_stock-sold_count）。
     */
    private AdminFlashSaleVO toAdminVO(PlatformFlashSale scene) {
        AdminFlashSaleVO vo = new AdminFlashSaleVO();
        vo.setId(scene.getId());
        vo.setTitle(scene.getTitle());
        vo.setStatus(scene.getStatus());
        vo.setStatusText(statusText(scene.getStatus()));
        vo.setStartAt(scene.getStartAt());
        vo.setEndAt(scene.getEndAt());
        vo.setSort(scene.getSort());
        vo.setCampusId(scene.getCampusId());
        List<PlatformFlashSaleItem> saleItems = flashSaleItemMapper.selectList(
                Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                        .eq(PlatformFlashSaleItem::getFlashSaleId, scene.getId())
                        .orderByAsc(PlatformFlashSaleItem::getSort)
                        .orderByAsc(PlatformFlashSaleItem::getId));
        if (saleItems.isEmpty()) {
            vo.setItems(List.of());
            return vo;
        }
        List<Long> itemIds = saleItems.stream().map(PlatformFlashSaleItem::getItemId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, PlatformItemRow> itemById = itemIds.isEmpty() ? Map.of()
                : itemMapper.selectPlatformItemsByIds(itemIds).stream()
                .collect(Collectors.toMap(PlatformItemRow::getId, row -> row, (a, b) -> a));
        List<AdminFlashSaleItemVO> items = new ArrayList<>(saleItems.size());
        for (PlatformFlashSaleItem saleItem : saleItems) {
            PlatformItemRow item = itemById.get(saleItem.getItemId());
            int stock = saleItem.getSeckillStock() == null ? 0 : saleItem.getSeckillStock();
            int sold = saleItem.getSoldCount() == null ? 0 : saleItem.getSoldCount();
            int left = cacheService.seckillLeftStock(saleItem.getItemId());
            if (left < 0) {
                left = Math.max(stock - sold, 0);
            }
            AdminFlashSaleItemVO itemVO = new AdminFlashSaleItemVO();
            itemVO.setItemId(saleItem.getItemId());
            itemVO.setTitle(item == null ? "（商品已删除）" : item.getTitle());
            itemVO.setCoverUrl(item == null ? null : item.getCoverUrl());
            itemVO.setOriginPrice(item == null ? null : item.getPrice());
            itemVO.setSeckillPrice(saleItem.getSeckillPrice());
            itemVO.setSeckillStock(stock);
            itemVO.setPerUserLimit(saleItem.getPerUserLimit() == null ? 1 : saleItem.getPerUserLimit());
            itemVO.setSoldCount(sold);
            itemVO.setLeftStock(left);
            itemVO.setSort(saleItem.getSort());
            items.add(itemVO);
        }
        vo.setItems(items);
        return vo;
    }

    /** 场次字段归一 + 校验（新建/编辑共用） */
    private void applySceneFields(PlatformFlashSale scene, FlashSaleSaveDTO dto, boolean create) {
        if (dto.getTitle() == null || dto.getTitle().trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "场次标题不能为空");
        }
        scene.setTitle(dto.getTitle().trim());
        if (dto.getCampusId() != null) {
            scene.setCampusId(dto.getCampusId());
        } else if (create) {
            scene.setCampusId(DEFAULT_CAMPUS_ID);
        }
        LocalDateTime startAt = PlatformTimeUtils.parse(dto.getStartAt());
        LocalDateTime endAt = PlatformTimeUtils.parse(dto.getEndAt());
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new BizException(ResultCode.PARAM_ERROR, "场次开始时间必须早于结束时间");
        }
        scene.setStartAt(startAt);
        scene.setEndAt(endAt);
        if (dto.getStatus() != null) {
            if (dto.getStatus() != STATUS_PREVIEW && dto.getStatus() != STATUS_RUNNING
                    && dto.getStatus() != STATUS_ENDED) {
                throw new BizException(ResultCode.PARAM_ERROR, "场次状态只能是 1（预告）/ 2（进行中）/ 3（已结束）");
            }
            scene.setStatus(dto.getStatus());
        } else if (create) {
            scene.setStatus(STATUS_PREVIEW);
        }
        if (dto.getSort() != null) {
            scene.setSort(dto.getSort());
        } else if (create) {
            scene.setSort(0);
        }
    }

    private FlashSaleVO loadCurrent(Long campusId) {
        PlatformFlashSale scene = flashSaleMapper.selectCurrent(campusId);
        if (scene == null) {
            return null;
        }
        FlashSaleVO vo = new FlashSaleVO();
        vo.setId(scene.getId());
        vo.setTitle(scene.getTitle());
        vo.setStartAt(scene.getStartAt());
        vo.setEndAt(scene.getEndAt());
        vo.setStatus(STATUS_RUNNING);
        vo.setRemainSeconds(0L);
        List<PlatformFlashSaleItem> saleItems = flashSaleItemMapper.selectList(
                Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                        .eq(PlatformFlashSaleItem::getFlashSaleId, scene.getId())
                        .orderByAsc(PlatformFlashSaleItem::getSort)
                        .orderByAsc(PlatformFlashSaleItem::getId));
        List<FlashSaleItemVO> items = new ArrayList<>();
        if (!saleItems.isEmpty()) {
            List<Long> itemIds = saleItems.stream().map(PlatformFlashSaleItem::getItemId)
                    .filter(Objects::nonNull).distinct().toList();
            Map<Long, PlatformItemRow> itemById = itemIds.isEmpty() ? Map.of()
                    : itemMapper.selectPlatformItemsByIds(itemIds).stream()
                    .collect(Collectors.toMap(PlatformItemRow::getId, row -> row, (a, b) -> a));
            for (PlatformFlashSaleItem saleItem : saleItems) {
                PlatformItemRow item = itemById.get(saleItem.getItemId());
                if (item == null) {
                    continue; // 商品被删/不存在就不展示，避免空卡片
                }
                FlashSaleItemVO itemVO = new FlashSaleItemVO();
                itemVO.setItemId(saleItem.getItemId());
                itemVO.setTitle(item.getTitle());
                itemVO.setCoverUrl(item.getCoverUrl());
                itemVO.setOriginPrice(item.getPrice());
                itemVO.setSeckillPrice(saleItem.getSeckillPrice());
                itemVO.setStock(saleItem.getSeckillStock() == null ? 0 : saleItem.getSeckillStock());
                itemVO.setSoldCount(saleItem.getSoldCount() == null ? 0 : saleItem.getSoldCount());
                itemVO.setLeftStock(Math.max(itemVO.getStock() - itemVO.getSoldCount(), 0));
                itemVO.setPerUserLimit(saleItem.getPerUserLimit() == null ? 1 : saleItem.getPerUserLimit());
                itemVO.setGrabbed(false);
                items.add(itemVO);
            }
        }
        vo.setItems(items);
        return vo;
    }

    private Set<Long> grabbedItemIds(Long userId, List<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (PlatformSeckillRecord record : seckillRecordMapper.selectList(
                Wrappers.<PlatformSeckillRecord>lambdaQuery()
                        .eq(PlatformSeckillRecord::getUserId, userId)
                        .in(PlatformSeckillRecord::getItemId, itemIds))) {
            ids.add(record.getItemId());
        }
        return ids;
    }

    /** 该用户是否已抢到过这件秒杀商品（DB 唯一键口径） */
    private boolean alreadyGrabbed(Long itemId, Long userId) {
        return seckillRecordMapper.selectCount(Wrappers.<PlatformSeckillRecord>lambdaQuery()
                .eq(PlatformSeckillRecord::getItemId, itemId)
                .eq(PlatformSeckillRecord::getUserId, userId)) > 0;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 预留：场次状态文案（本轮公开接口只返回进行中场次，状态恒为 2） */
    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_PREVIEW -> "预告";
            case STATUS_RUNNING -> "进行中";
            case STATUS_ENDED -> "已结束";
            default -> "未知";
        };
    }
}
