package com.ershou.campus.module.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.item.mapper.ItemCategoryMapper;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.platform.config.PlatformCacheKeys;
import com.ershou.campus.module.platform.config.PlatformProperties;
import com.ershou.campus.module.platform.dto.AdminItemUpdateDTO;
import com.ershou.campus.module.platform.dto.PlatformBuyDTO;
import com.ershou.campus.module.platform.dto.PlatformItemPublishDTO;
import com.ershou.campus.module.platform.entity.PlatformConsignPoint;
import com.ershou.campus.module.platform.entity.PlatformCoupon;
import com.ershou.campus.module.platform.entity.PlatformFlashSale;
import com.ershou.campus.module.platform.entity.PlatformFlashSaleItem;
import com.ershou.campus.module.platform.entity.PlatformItemRow;
import com.ershou.campus.module.platform.mapper.PlatformConsignPointMapper;
import com.ershou.campus.module.platform.mapper.PlatformCouponMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleItemMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleMapper;
import com.ershou.campus.module.platform.mapper.PlatformItemMapper;
import com.ershou.campus.module.platform.vo.AdminPlatformItemVO;
import com.ershou.campus.module.platform.vo.PlatformBuyVO;
import com.ershou.campus.module.platform.vo.PlatformItemVO;
import com.ershou.campus.module.platform.vo.PlatformOverviewVO;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.ershou.campus.module.search.service.ItemIndexService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台商品服务：专区列表 / 后台发布 / 直购下单（设计 3.2、3.4、3.3）。
 *
 * <p>对 item 表的读写全部走 {@link PlatformItemMapper} 的自有 SQL，
 * 不依赖 item 模块的实体与服务（设计 3.1 明确要求）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformItemService {

    private static final int STATUS_ON_SALE = 2;
    private static final int MAX_PAGE_SIZE = 100;
    /** 概览缓存 TTL：设计 60s，取 54s 给 JsonCache 的 +0~10% 抖动留余量（实际 TTL 不超过 60s） */
    private static final Duration OVERVIEW_TTL = Duration.ofSeconds(54);
    private static final TypeReference<List<String>> IMAGES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<PlatformOverviewVO> OVERVIEW_TYPE = new TypeReference<>() {
    };

    private final PlatformItemMapper itemMapper;
    private final PlatformConsignPointMapper pointMapper;
    private final PlatformCouponMapper couponMapper;
    private final PlatformFlashSaleMapper flashSaleMapper;
    private final PlatformFlashSaleItemMapper flashSaleItemMapper;
    private final ItemCategoryMapper categoryMapper;
    private final CampusMapper campusMapper;
    private final PlatformCacheService cacheService;
    private final PlatformProperties platformProperties;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;
    private final OrderService orderService;
    private final JsonCache jsonCache;
    private final ObjectMapper json;

    // ---------------------------------------------------------------- 专区列表

    /** 平台专区商品分页：只返回 seller_type=2 且 status=2，叠加当前场次秒杀价（设计 3.2） */
    public PageResult<PlatformItemVO> page(Long campusId, Long pointId, Long categoryId, String sort,
                                           long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        long total = itemMapper.countPlatformItems(campusId, pointId, categoryId);
        if (total <= 0) {
            return PageResult.of(List.of(), 0, num, size);
        }
        int offset = (int) Math.min((num - 1) * size, Integer.MAX_VALUE);
        List<PlatformItemVO> records = new ArrayList<>(itemMapper.selectPlatformItems(
                        campusId, pointId, categoryId, normalizeSort(sort), offset, (int) size).stream()
                .map(this::toVO).toList());
        applySeckill(records, campusId);
        return PageResult.of(records, total, num, size);
    }

    /** 按 id 取平台商品详情（后台发布后的回显 / 内部复用） */
    public PlatformItemVO detail(Long itemId) {
        return toVO(itemMapper.selectPlatformItem(itemId));
    }

    // ================================================================
    // 第 3 轮 · 运营台平台商品管理（设计 3.2 / 3.8）
    // ================================================================

    /** 运营台商品分页：含全部状态，支持关键词/代卖点/状态筛选 */
    public PageResult<AdminPlatformItemVO> adminPage(Long campusId, Long pointId, Integer status,
                                                     String keyword, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        long total = itemMapper.countAdminItems(campusId, pointId, status, kw);
        if (total <= 0) {
            return PageResult.of(List.of(), 0, num, size);
        }
        int offset = (int) Math.min((num - 1) * size, Integer.MAX_VALUE);
        List<AdminPlatformItemVO> records = itemMapper.selectAdminItems(
                campusId, pointId, status, kw, offset, (int) size);
        records.forEach(this::fillAdminItemVO);
        return PageResult.of(records, total, num, size);
    }

    /** 运营台编辑平台商品（标题/价格/库存/代卖点）；清 item 缓存 + 同步 ES + 清概览缓存 */
    @Transactional(rollbackFor = Exception.class)
    public AdminPlatformItemVO adminUpdate(Long itemId, AdminItemUpdateDTO dto) {
        PlatformItemRow row = itemMapper.selectPlatformItem(itemId);
        if (row == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "平台商品不存在");
        }
        String title = null;
        if (dto.getTitle() != null) {
            if (!StringUtils.hasText(dto.getTitle())) {
                throw new BizException(ResultCode.PARAM_ERROR, "商品标题不能为空");
            }
            title = dto.getTitle().trim();
        }
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
        itemMapper.updateAdminItem(itemId, title, dto.getPrice(), dto.getStock(), pointId);
        Long campusId = row.getCampusId();
        TxHooks.afterCommit(() -> {
            itemIndexService.syncById(itemId);
            evictor.itemChanged(itemId);
            cacheService.evictOverview(campusId);
        });
        log.info("[platform][admin-item][update][{}] title={} price={} stock={} point={}",
                itemId, title, dto.getPrice(), dto.getStock(), pointId);
        return toAdminItem(itemId);
    }

    /** 运营台下架：status=5；清缓存 + 同步 ES + 清概览缓存 */
    @Transactional(rollbackFor = Exception.class)
    public void adminOffline(Long itemId) {
        PlatformItemRow row = itemMapper.selectPlatformItem(itemId);
        if (row == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "平台商品不存在");
        }
        itemMapper.offlineItem(itemId);
        Long campusId = row.getCampusId();
        TxHooks.afterCommit(() -> {
            itemIndexService.syncById(itemId);
            evictor.itemChanged(itemId);
            cacheService.evictOverview(campusId);
        });
        log.info("[platform][admin-item][offline][{}] {} -> 5", itemId, row.getStatus());
    }

    /** 运营台重新上架：status=2、有效期 +30 天；清缓存 + 同步 ES + 清概览缓存 */
    @Transactional(rollbackFor = Exception.class)
    public void adminOnline(Long itemId) {
        PlatformItemRow row = itemMapper.selectPlatformItem(itemId);
        if (row == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "平台商品不存在");
        }
        LocalDateTime expireAt = LocalDateTime.now()
                .plusDays(Math.max(platformProperties.getItemValidDays(), 1));
        itemMapper.onlineItem(itemId, expireAt);
        Long campusId = row.getCampusId();
        TxHooks.afterCommit(() -> {
            itemIndexService.syncById(itemId);
            evictor.itemChanged(itemId);
            cacheService.evictOverview(campusId);
        });
        log.info("[platform][admin-item][online][{}] {} -> 2 expireAt={}", itemId, row.getStatus(), expireAt);
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
        vo.setPlatformItemCount(itemMapper.countOnSaleByCampus(campusId == null ? 0L : campusId));
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

    // ---------------------------------------------------------------- 后台发布

    /**
     * 发布平台商品（设计 3.4）：seller_id=平台账号、seller_type=2、免审核直接 status=2、
     * 有效期 +30 天；同步 ES 索引 + 清商品/概览缓存。
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
        if (categoryMapper.selectById(dto.getCategoryId()) == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "商品分类不存在");
        }
        List<String> images = dto.getImages() == null ? List.of() : dto.getImages().stream()
                .filter(StringUtils::hasText).map(String::trim).toList();
        if (images.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "至少上传一张商品图片");
        }

        int stock = dto.getStock() == null || dto.getStock() < 1 ? 1 : dto.getStock();
        LocalDateTime now = LocalDateTime.now();
        PlatformItemRow row = new PlatformItemRow();
        row.setSellerId(platformProperties.getSellerId());
        row.setSellerType(2);
        row.setSchoolId(schoolIdOf(campusId));
        row.setCampusId(campusId);
        row.setTitle(dto.getTitle().trim());
        row.setDescription(dto.getDescription());
        row.setCategoryId(dto.getCategoryId());
        row.setConditionLevel(dto.getConditionLevel() == null ? 3 : dto.getConditionLevel());
        row.setPrice(dto.getPrice());
        row.setOriginalPrice(dto.getOriginalPrice() == null ? dto.getPrice() : dto.getOriginalPrice());
        row.setNegotiable(0);
        row.setStock(stock);
        row.setIsUnique(stock <= 1 ? 1 : 0);
        row.setCoverUrl(images.get(0));
        row.setImages(writeJson(images));
        row.setTradeMode(1);
        // 平台点只通过 platform_point_id 关联；prefer_point_id 指向 trade_point（另一套 id 空间），
        // 写进去会让首页卡片用 trade_point.id 同 id 的名字渲染，显示错误地名（Lead 2026-10-17 审阅修正）。
        row.setPreferPointId(null);
        row.setPlatformPointId(point.getId());
        row.setStatus(STATUS_ON_SALE);
        row.setPublishedAt(now);
        row.setExpireAt(now.plusDays(Math.max(platformProperties.getItemValidDays(), 1)));

        itemMapper.insertPlatformItem(row);
        Long itemId = row.getId();
        Long itemCampusId = campusId;
        TxHooks.afterCommit(() -> {
            itemIndexService.syncById(itemId);
            evictor.itemChanged(itemId);
            cacheService.evictOverview(itemCampusId);
        });
        log.info("[platform][item][publish][{}] seller={} campus={} point={} price={} stock={}",
                itemId, row.getSellerId(), campusId, point.getId(), row.getPrice(), stock);
        return detail(itemId);
    }

    // ---------------------------------------------------------------- 直购下单

    /**
     * 平台商品直购（设计 3.3）：校验在售/库存后调 {@code OrderService#platformBuy} 建平台单。
     *
     * <p>couponId 本轮<b>不抵扣</b>，只记录日志（TODO(round3)，设计第八节）。
     */
    public PlatformBuyVO buy(Long itemId, Long userId, PlatformBuyDTO dto) {
        int quantity = dto == null || dto.getQuantity() == null || dto.getQuantity() < 1 ? 1 : dto.getQuantity();
        if (dto != null && dto.getCouponId() != null) {
            // TODO(round3): 优惠券实际抵扣结算（本轮只领券，不参与下单金额）
            log.info("[platform][buy] couponId={} 本轮暂不抵扣 item={} buyer={}", dto.getCouponId(), itemId, userId);
        }
        PlatformItemRow item = itemMapper.selectPlatformItem(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "平台商品不存在或已下架");
        }
        if (item.getStatus() == null || item.getStatus() != STATUS_ON_SALE) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品当前不可购买");
        }
        if (item.getStock() != null && item.getStock() < quantity) {
            throw new BizException(ResultCode.ITEM_LOCKED, "库存不足（剩余 " + item.getStock() + " 件）");
        }
        Map<String, Object> result = orderService.platformBuy(
                itemId, userId, quantity, item.getPrice(), item.getPlatformPointId());
        return toBuyVO(result);
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

    private Long schoolIdOf(Long campusId) {
        if (campusId == null) {
            return null;
        }
        Campus campus = campusMapper.selectById(campusId);
        return campus == null ? null : campus.getSchoolId();
    }

    private PlatformItemVO toVO(PlatformItemRow row) {
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
        vo.setPublishedAt(row.getPublishedAt());
        vo.setPointId(row.getPlatformPointId());
        vo.setPointName(row.getPointName());
        vo.setSellerType(2);
        return vo;
    }

    /** 运营台视角单件商品（含状态文案） */
    private AdminPlatformItemVO toAdminItem(Long itemId) {
        AdminPlatformItemVO vo = itemMapper.selectAdminItem(itemId);
        if (vo != null) {
            fillAdminItemVO(vo);
        }
        return vo;
    }

    private void fillAdminItemVO(AdminPlatformItemVO vo) {
        vo.setStatusText(statusText(vo.getStatus()));
        vo.setSellerType(2);
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

    private String writeJson(List<String> images) {
        try {
            return json.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception e) {
            return "[]";
        }
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

    private PlatformBuyVO toBuyVO(Map<String, Object> result) {
        PlatformBuyVO vo = new PlatformBuyVO();
        if (result == null) {
            return vo;
        }
        vo.setOrderId(asLong(result.get("orderId")));
        vo.setOrderNo(asString(result.get("orderNo")));
        vo.setItemId(asLong(result.get("itemId")));
        vo.setDealPrice(asDecimal(result.get("dealPrice")));
        vo.setPointId(asLong(result.get("pointId")));
        vo.setItemTitle(asString(result.get("itemTitle")));
        vo.setItemCover(asString(result.get("itemCover")));
        return vo;
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

    private BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
