package com.ershou.cloud.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.platform.cache.PlatformJsonCache;
import com.ershou.cloud.platform.config.PlatformCacheKeys;
import com.ershou.cloud.platform.config.PlatformProperties;
import com.ershou.cloud.platform.dto.AdminItemUpdateDTO;
import com.ershou.cloud.platform.dto.PlatformBuyDTO;
import com.ershou.cloud.platform.dto.PlatformItemPublishDTO;
import com.ershou.cloud.platform.entity.PlatformConsignPoint;
import com.ershou.cloud.platform.entity.PlatformCoupon;
import com.ershou.cloud.platform.entity.PlatformFlashSale;
import com.ershou.cloud.platform.entity.PlatformFlashSaleItem;
import com.ershou.cloud.platform.feign.ItemClient;
import com.ershou.cloud.platform.feign.OrderClient;
import com.ershou.cloud.platform.feign.model.InnerItemVO;
import com.ershou.cloud.platform.feign.model.PlatformBuyResultVO;
import com.ershou.cloud.platform.feign.model.PlatformItemSummaryVO;
import com.ershou.cloud.platform.feign.model.PlatformOrderCreateDTO;
import com.ershou.cloud.platform.mapper.PlatformConsignPointMapper;
import com.ershou.cloud.platform.mapper.PlatformCouponMapper;
import com.ershou.cloud.platform.mapper.PlatformFlashSaleItemMapper;
import com.ershou.cloud.platform.mapper.PlatformFlashSaleMapper;
import com.ershou.cloud.platform.util.PlatformTimeUtils;
import com.ershou.cloud.platform.vo.AdminPlatformItemVO;
import com.ershou.cloud.platform.vo.PlatformBuyVO;
import com.ershou.cloud.platform.vo.PlatformItemVO;
import com.ershou.cloud.platform.vo.PlatformOverviewVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台商品服务（全量拆分版）：专区列表 / 后台发布编辑上下架 / 直购下单 / 专区概览。
 *
 * <p>item 表数据归 item-service 所有，本服务<b>不做任何 item 表 SQL</b>：
 * <ul>
 *   <li>读写全部走 {@link ItemClient}（{@code /inner/items/platform/**}）；</li>
 *   <li>平台单落库走 {@link OrderClient}（{@code /inner/orders/platform}）；</li>
 *   <li>本库只保留代卖点/券/秒杀场次（ershou_platform）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformItemService {

    private static final int STATUS_ON_SALE = 2;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int SELLER_TYPE_PLATFORM = 2;
    /** 概览缓存 TTL：设计 60s，取 54s 给 TTL 抖动留余量（实际 TTL 不超过 60s） */
    private static final Duration OVERVIEW_TTL = Duration.ofSeconds(54);
    private static final TypeReference<List<String>> IMAGES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<PlatformOverviewVO> OVERVIEW_TYPE = new TypeReference<>() {
    };

    private final ItemClient itemClient;
    private final OrderClient orderClient;
    private final PlatformConsignPointMapper pointMapper;
    private final PlatformCouponMapper couponMapper;
    private final PlatformFlashSaleMapper flashSaleMapper;
    private final PlatformFlashSaleItemMapper flashSaleItemMapper;
    private final PlatformCacheService cacheService;
    private final PlatformProperties platformProperties;
    private final PlatformJsonCache jsonCache;
    private final ObjectMapper json;

    // ---------------------------------------------------------------- 专区列表

    /** 平台专区商品分页：只返回 seller_type=2 且 status=2，叠加当前场次秒杀价（设计 3.2） */
    public PageResult<PlatformItemVO> page(Long campusId, Long pointId, Long categoryId, String sort,
                                           long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Result<PageResult<InnerItemVO>> result = itemClient.platformPage(
                campusId, pointId, categoryId, STATUS_ON_SALE, null, normalizeSort(sort), num, size);
        PageResult<InnerItemVO> page = dataOf(result, "平台商品分页");
        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return PageResult.of(List.of(), page == null ? 0 : page.getTotal(), num, size);
        }
        Map<Long, String> pointNames = pointNames(page.getRecords().stream()
                .map(InnerItemVO::getPlatformPointId).filter(Objects::nonNull).toList());
        List<PlatformItemVO> records = new ArrayList<>(page.getRecords().size());
        for (InnerItemVO row : page.getRecords()) {
            records.add(toVO(row, pointNames));
        }
        applySeckill(records, campusId);
        return PageResult.of(records, page.getTotal(), page.getPageNum() > 0 ? page.getPageNum() : num,
                page.getPageSize() > 0 ? page.getPageSize() : size);
    }

    /** 按 id 取平台商品详情（后台发布后的回显 / 内部复用） */
    public PlatformItemVO detail(Long itemId) {
        InnerItemVO row = requirePlatformItem(itemId);
        List<Long> pointIds = row.getPlatformPointId() == null ? List.of() : List.of(row.getPlatformPointId());
        return toVO(row, pointNames(pointIds));
    }

    // ================================================================
    // 运营台 · 平台商品管理（设计 3.2 / 3.8）
    // ================================================================

    /** 运营台商品分页：含全部状态，支持关键词/代卖点/状态筛选 */
    public PageResult<AdminPlatformItemVO> adminPage(Long campusId, Long pointId, Integer status,
                                                     String keyword, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        Result<PageResult<InnerItemVO>> result = itemClient.platformPage(
                campusId, pointId, null, status, kw, null, num, size);
        PageResult<InnerItemVO> page = dataOf(result, "平台商品分页");
        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return PageResult.of(List.of(), page == null ? 0 : page.getTotal(), num, size);
        }
        Map<Long, String> pointNames = pointNames(page.getRecords().stream()
                .map(InnerItemVO::getPlatformPointId).filter(Objects::nonNull).toList());
        List<AdminPlatformItemVO> records = new ArrayList<>(page.getRecords().size());
        for (InnerItemVO row : page.getRecords()) {
            records.add(toAdminItem(row, pointNames));
        }
        return PageResult.of(records, page.getTotal(), page.getPageNum() > 0 ? page.getPageNum() : num,
                page.getPageSize() > 0 ? page.getPageSize() : size);
    }

    /** 运营台编辑平台商品（标题/价格/库存/代卖点）；item-service 负责清缓存/同步 ES */
    @Transactional(rollbackFor = Exception.class)
    public AdminPlatformItemVO adminUpdate(Long itemId, AdminItemUpdateDTO dto) {
        InnerItemVO row = requirePlatformItem(itemId);
        Long pointId = dto.getPlatformPointId();
        if (pointId != null) {
            PlatformConsignPoint point = pointMapper.selectById(pointId);
            if (point == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "代卖点不存在");
            }
            if (point.getCampusId() != null && row.getCampusId() != null
                    && !point.getCampusId().equals(row.getCampusId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "代卖点不属于该商品所在校区");
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (dto.getTitle() != null) {
            if (!StringUtils.hasText(dto.getTitle())) {
                throw new BizException(ResultCode.PARAM_ERROR, "商品标题不能为空");
            }
            body.put("title", dto.getTitle().trim());
        }
        if (dto.getPrice() != null) {
            body.put("price", dto.getPrice());
        }
        if (dto.getStock() != null) {
            body.put("stock", dto.getStock());
        }
        if (pointId != null) {
            body.put("platformPointId", pointId);
        }
        if (body.isEmpty()) {
            return toAdminItem(row, pointNames(List.of()));
        }
        Result<InnerItemVO> updated = itemClient.platformUpdate(itemId, body);
        InnerItemVO after = dataOf(updated, "平台商品编辑");
        if (after == null) {
            after = row;
        }
        evictOverview(row.getCampusId());
        log.info("[platform][admin-item][update][{}] title={} price={} stock={} point={}",
                itemId, body.get("title"), dto.getPrice(), dto.getStock(), pointId);
        return toAdminItem(after, pointNames(List.of()));
    }

    /** 运营台下架：status=5；item-service 负责清缓存/同步 ES，本服务只清概览缓存 */
    @Transactional(rollbackFor = Exception.class)
    public void adminOffline(Long itemId) {
        InnerItemVO row = requirePlatformItem(itemId);
        requireOk(itemClient.platformOffline(itemId, Map.of("reason", "平台运营台下架")), "平台商品下架");
        evictOverview(row.getCampusId());
        log.info("[platform][admin-item][offline][{}] {} -> 5", itemId, row.getStatus());
    }

    /** 运营台重新上架：status=2、有效期由 item-service 续期 */
    @Transactional(rollbackFor = Exception.class)
    public void adminOnline(Long itemId) {
        InnerItemVO row = requirePlatformItem(itemId);
        requireOk(itemClient.platformOnline(itemId, platformProperties.getItemValidDays()), "平台商品上架");
        evictOverview(row.getCampusId());
        log.info("[platform][admin-item][online][{}] {} -> 2", itemId, row.getStatus());
    }

    // ---------------------------------------------------------------- 专区概览

    /**
     * 专区概览（设计 3.2）：在售平台商品数 / 启用点位数 / 进行中券数 / 当前秒杀场次。
     * 聚合结果缓存 60s（写路径：点位增删改、商品发布会主动清理）。
     */
    public PlatformOverviewVO overview(Long campusId) {
        Long keyCampus = campusId == null ? 0L : campusId;
        return jsonCache.getOrLoad(PlatformCacheKeys.overview(keyCampus), OVERVIEW_TTL, OVERVIEW_TYPE,
                () -> loadOverview(campusId));
    }

    private PlatformOverviewVO loadOverview(Long campusId) {
        LocalDateTime now = LocalDateTime.now();
        PlatformOverviewVO vo = new PlatformOverviewVO();
        vo.setPlatformItemCount(platformItemOnSale(campusId));
        vo.setPointCount(pointMapper.selectCount(Wrappers.<PlatformConsignPoint>lambdaQuery()
                .eq(campusId != null, PlatformConsignPoint::getCampusId, campusId)
                .eq(PlatformConsignPoint::getStatus, 1)));
        // "可领券"= 启用且在时间窗内、且还剩库存（库存实时读 Redis，未预热时按 DB 总数判断）
        List<PlatformCoupon> activeCoupons = couponMapper.selectList(Wrappers.<PlatformCoupon>lambdaQuery()
                .eq(campusId != null, PlatformCoupon::getCampusId, campusId)
                .eq(PlatformCoupon::getStatus, 1)
                .le(PlatformCoupon::getStartAt, now)
                .ge(PlatformCoupon::getEndAt, now));
        vo.setCouponCount(activeCoupons.stream().filter(coupon -> {
            int left = cacheService.couponLeftStock(coupon.getId());
            return left < 0 ? (coupon.getTotalStock() != null && coupon.getTotalStock() > 0) : left > 0;
        }).count());
        PlatformFlashSale scene = campusId == null ? null : flashSaleMapper.selectCurrent(campusId);
        if (scene != null) {
            vo.setFlashSaleId(scene.getId());
            vo.setFlashSaleTitle(scene.getTitle());
            vo.setFlashSaleStatus(scene.getStatus());
        }
        return vo;
    }

    private long platformItemOnSale(Long campusId) {
        Result<PlatformItemSummaryVO> result = itemClient.platformSummary(campusId);
        if (result != null && result.getCode() == ResultCode.SUCCESS.getCode() && result.getData() != null) {
            return result.getData().resolveOnSale();
        }
        log.warn("[platform][overview] 商品服务汇总不可用，在售数按 0 展示：{}",
                result == null ? null : result.getMessage());
        return 0L;
    }

    // ---------------------------------------------------------------- 后台发布

    /**
     * 发布平台商品（设计 3.4）：seller_id=平台账号、seller_type=2、免审核直接 status=2、
     * 有效期 +30 天；落库/ES/缓存由 item-service 负责（POST /inner/items/platform）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PlatformItemVO publish(PlatformItemPublishDTO dto) {
        PlatformConsignPoint point = pointMapper.selectById(dto.getPlatformPointId());
        if (point == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "代卖点不存在");
        }
        Long campusId = dto.getCampusId() != null ? dto.getCampusId() : point.getCampusId();
        if (point.getCampusId() == null || !point.getCampusId().equals(campusId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "代卖点不属于该校区");
        }
        List<String> images = dto.getImages() == null ? List.of() : dto.getImages().stream()
                .filter(StringUtils::hasText).map(String::trim).toList();
        if (images.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "至少上传一张商品图片");
        }
        int stock = dto.getStock() == null || dto.getStock() < 1 ? 1 : dto.getStock();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", dto.getTitle().trim());
        body.put("description", dto.getDescription());
        body.put("categoryId", dto.getCategoryId());
        body.put("conditionLevel", dto.getConditionLevel() == null ? 3 : dto.getConditionLevel());
        body.put("price", dto.getPrice());
        body.put("originalPrice", dto.getOriginalPrice() == null ? dto.getPrice() : dto.getOriginalPrice());
        body.put("stock", stock);
        body.put("images", images);
        body.put("platformPointId", point.getId());
        body.put("campusId", campusId);
        body.put("sellerId", platformProperties.getSellerId());
        body.put("sellerType", SELLER_TYPE_PLATFORM);

        Result<Map<String, Object>> created = itemClient.platformCreate(body);
        Map<String, Object> data = dataOf(created, "平台商品发布");
        Long itemId = data == null ? null : asLong(data.get("itemId"));
        if (itemId == null) {
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "平台商品发布失败：商品服务未返回 itemId");
        }
        evictOverview(campusId);
        log.info("[platform][item][publish][{}] seller={} campus={} point={} price={} stock={}",
                itemId, platformProperties.getSellerId(), campusId, point.getId(), dto.getPrice(), stock);
        // 创建接口只返回精简字段：这里回查一次详情，保证与运营台/商城返回结构一致
        Result<InnerItemVO> detail = itemClient.platformDetail(itemId);
        if (detail != null && detail.getCode() == ResultCode.SUCCESS.getCode() && detail.getData() != null) {
            return toVO(detail.getData(), pointNames(List.of(point.getId())));
        }
        PlatformItemVO vo = new PlatformItemVO();
        vo.setId(itemId);
        vo.setTitle(dto.getTitle().trim());
        vo.setDescription(dto.getDescription());
        vo.setPrice(dto.getPrice());
        vo.setOriginalPrice(dto.getOriginalPrice() == null ? dto.getPrice() : dto.getOriginalPrice());
        vo.setCategoryId(dto.getCategoryId());
        vo.setConditionLevel(dto.getConditionLevel() == null ? 3 : dto.getConditionLevel());
        vo.setStock(stock);
        vo.setStatus(STATUS_ON_SALE);
        vo.setStatusText(statusText(STATUS_ON_SALE));
        vo.setImages(images);
        vo.setCoverUrl(images.isEmpty() ? null : images.get(0));
        vo.setPointId(point.getId());
        vo.setPointName(point.getName());
        vo.setSellerType(SELLER_TYPE_PLATFORM);
        return vo;
    }

    // ---------------------------------------------------------------- 直购下单

    /**
     * 平台商品直购（设计 3.3）：校验在售/库存后调 order-service 建平台单。
     *
     * <p>couponId 本轮<b>不抵扣</b>，只记录日志（TODO(round3)，设计第八节）。
     */
    public PlatformBuyVO buy(Long itemId, Long userId, PlatformBuyDTO dto) {
        int quantity = dto == null || dto.getQuantity() == null || dto.getQuantity() < 1 ? 1 : dto.getQuantity();
        if (dto != null && dto.getCouponId() != null) {
            log.info("[platform][buy] couponId={} 本轮暂不抵扣 item={} buyer={}", dto.getCouponId(), itemId, userId);
        }
        InnerItemVO item = requirePlatformItem(itemId);
        if (item.getStatus() == null || item.getStatus() != STATUS_ON_SALE) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品当前不可购买");
        }
        if (item.getStock() != null && item.getStock() < quantity) {
            throw new BizException(ResultCode.ITEM_LOCKED, "库存不足（剩余 " + item.getStock() + " 件）");
        }
        PlatformOrderCreateDTO create = new PlatformOrderCreateDTO();
        create.setItemId(itemId);
        create.setBuyerId(userId);
        create.setQuantity(quantity);
        create.setDealPrice(item.getPrice());
        create.setPointId(item.getPlatformPointId());
        Result<PlatformBuyResultVO> result = orderClient.createPlatformOrder(create);
        PlatformBuyResultVO order = dataOf(result, "平台下单");
        if (order == null) {
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "平台下单失败，请稍后重试");
        }
        PlatformBuyVO vo = toBuyVO(order);
        // 下游返回的 Map 可能不带 itemId（order-service 当前版本），用请求参数兜底，保持与单体 JSON 一致
        if (vo.getItemId() == null) {
            vo.setItemId(itemId);
        }
        return vo;
    }

    // ---------------------------------------------------------------- 内部

    /** 叠加当前秒杀场次的秒杀价 / 剩余库存（列表卡片用；非秒杀商品保持 null） */
    private void applySeckill(List<PlatformItemVO> records, Long campusId) {
        if (records.isEmpty() || campusId == null) {
            return;
        }
        try {
            PlatformFlashSale scene = flashSaleMapper.selectCurrent(campusId);
            if (scene == null) {
                return;
            }
            List<PlatformFlashSaleItem> saleItems = flashSaleItemMapper.selectList(
                    Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                            .eq(PlatformFlashSaleItem::getFlashSaleId, scene.getId()));
            if (saleItems.isEmpty()) {
                return;
            }
            Map<Long, PlatformFlashSaleItem> byItemId = new HashMap<>();
            for (PlatformFlashSaleItem saleItem : saleItems) {
                byItemId.put(saleItem.getItemId(), saleItem);
            }
            for (PlatformItemVO vo : records) {
                PlatformFlashSaleItem saleItem = byItemId.get(vo.getId());
                if (saleItem == null) {
                    continue;
                }
                vo.setSeckillPrice(saleItem.getSeckillPrice());
                int left = cacheService.seckillLeftStock(vo.getId());
                if (left < 0) {
                    int stock = saleItem.getSeckillStock() == null ? 0 : saleItem.getSeckillStock();
                    int sold = saleItem.getSoldCount() == null ? 0 : saleItem.getSoldCount();
                    left = Math.max(stock - sold, 0);
                }
                vo.setSeckillStock(left);
            }
        } catch (Exception e) {
            // 秒杀信息是"锦上添花"，查不到不能让商品列表挂掉
            log.warn("[platform][items] 叠加秒杀信息失败，按普通商品展示：{}", e.getMessage());
        }
    }

    private String normalizeSort(String sort) {
        if ("price_asc".equals(sort) || "price_desc".equals(sort)) {
            return sort;
        }
        return "latest";
    }

    /** item-service 平台商品详情（不存在抛 30001，服务不可用抛 80001 文案） */
    private InnerItemVO requirePlatformItem(Long itemId) {
        if (itemId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少商品 id");
        }
        Result<InnerItemVO> result = itemClient.platformDetail(itemId);
        if (result == null) {
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, "商品服务暂不可用，请稍后重试");
        }
        if (result.getCode() == ResultCode.SUCCESS.getCode() && result.getData() != null) {
            return result.getData();
        }
        if (result.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "平台商品不存在或已下架");
        }
        throw new BizException(ResultCode.SERVICE_UNAVAILABLE,
                StringUtils.hasText(result.getMessage()) ? result.getMessage() : "商品服务暂不可用，请稍后重试");
    }

    private void requireOk(Result<?> result, String action) {
        if (result != null && result.getCode() == ResultCode.SUCCESS.getCode()) {
            return;
        }
        if (result != null && result.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "平台商品不存在");
        }
        throw new BizException(ResultCode.SERVICE_UNAVAILABLE,
                result == null || !StringUtils.hasText(result.getMessage())
                        ? action + "失败：商品服务暂不可用" : result.getMessage());
    }

    /** 取出下游 data；非成功按服务不可用异常（带下游文案） */
    private <T> T dataOf(Result<T> result, String action) {
        if (result == null) {
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, action + "失败：下游服务无响应");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode()) {
            int code = result.getCode();
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "下游服务暂不可用";
            if (code == ResultCode.ITEM_NOT_FOUND.getCode()) {
                throw new BizException(ResultCode.ITEM_NOT_FOUND, message);
            }
            if (code == ResultCode.ITEM_LOCKED.getCode()) {
                throw new BizException(ResultCode.ITEM_LOCKED, message);
            }
            if (code == ResultCode.ITEM_STATUS_ERROR.getCode()) {
                throw new BizException(ResultCode.ITEM_STATUS_ERROR, message);
            }
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, action + "失败：" + message);
        }
        return result.getData();
    }

    private Map<Long, String> pointNames(Collection<Long> pointIds) {
        List<Long> ids = pointIds == null ? List.of() : pointIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (PlatformConsignPoint point : pointMapper.selectBatchIds(ids)) {
            names.put(point.getId(), point.getName());
        }
        return names;
    }

    private void evictOverview(Long campusId) {
        if (campusId != null) {
            cacheService.evictOverview(campusId);
        }
    }

    private PlatformItemVO toVO(InnerItemVO row, Map<Long, String> pointNames) {
        if (row == null) {
            return null;
        }
        PlatformItemVO vo = new PlatformItemVO();
        vo.setId(row.getId());
        vo.setTitle(row.getTitle());
        vo.setDescription(row.getDescription());
        vo.setPrice(row.getPrice());
        vo.setOriginalPrice(row.getOriginalPrice());
        vo.setCoverUrl(row.getCoverUrl());
        vo.setImages(readImages(row.getImages()));
        vo.setCategoryId(row.getCategoryId());
        vo.setCategoryName(row.getCategoryName());
        vo.setConditionLevel(row.getConditionLevel());
        vo.setStock(row.getStock());
        vo.setStatus(row.getStatus());
        vo.setStatusText(statusText(row.getStatus()));
        vo.setPublishedAt(parseTime(row.getPublishedAt()));
        vo.setPointId(row.getPlatformPointId());
        vo.setPointName(pointNames.get(row.getPlatformPointId()));
        vo.setSellerType(SELLER_TYPE_PLATFORM);
        return vo;
    }

    /** 运营台视角单件商品（含状态文案） */
    private AdminPlatformItemVO toAdminItem(InnerItemVO row, Map<Long, String> pointNames) {
        if (row == null) {
            return null;
        }
        AdminPlatformItemVO vo = new AdminPlatformItemVO();
        vo.setId(row.getId());
        vo.setTitle(row.getTitle());
        vo.setPrice(row.getPrice());
        vo.setOriginalPrice(row.getOriginalPrice());
        vo.setCoverUrl(row.getCoverUrl());
        vo.setStock(row.getStock());
        vo.setStatus(row.getStatus());
        vo.setStatusText(statusText(row.getStatus()));
        vo.setPointId(row.getPlatformPointId());
        vo.setPointName(pointNames.get(row.getPlatformPointId()));
        vo.setSellerType(SELLER_TYPE_PLATFORM);
        vo.setViewCount(row.getViewCount());
        vo.setPublishedAt(parseTime(row.getPublishedAt()));
        vo.setExpireAt(parseTime(row.getExpireAt()));
        return vo;
    }

    /** 与个人商品同一套状态文案（item 模块的 ItemService#statusText 口径保持一致） */
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

    private PlatformBuyVO toBuyVO(PlatformBuyResultVO result) {
        PlatformBuyVO vo = new PlatformBuyVO();
        vo.setOrderId(result.getOrderId());
        vo.setOrderNo(result.getOrderNo());
        vo.setItemId(result.getItemId());
        vo.setDealPrice(result.getDealPrice());
        vo.setPointId(result.getPointId());
        vo.setItemTitle(result.getItemTitle());
        vo.setItemCover(result.getItemCover());
        return vo;
    }

    private LocalDateTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return PlatformTimeUtils.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readImages(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                return json.readValue(text, IMAGES_TYPE);
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
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
}