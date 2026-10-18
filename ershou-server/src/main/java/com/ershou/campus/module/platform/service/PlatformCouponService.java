package com.ershou.campus.module.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.platform.config.PlatformCacheKeys;
import com.ershou.campus.module.platform.dto.CouponSaveDTO;
import com.ershou.campus.module.platform.entity.PlatformCoupon;
import com.ershou.campus.module.platform.entity.PlatformCouponUser;
import com.ershou.campus.module.platform.mapper.PlatformCouponMapper;
import com.ershou.campus.module.platform.mapper.PlatformCouponUserMapper;
import com.ershou.campus.module.platform.util.PlatformTimeUtils;
import com.ershou.campus.module.platform.vo.AdminCouponVO;
import com.ershou.campus.module.platform.vo.CouponClaimVO;
import com.ershou.campus.module.platform.vo.CouponVO;
import com.ershou.campus.module.platform.vo.MyCouponVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 平台优惠券服务（设计 3.2 列表 / 3.3 抢券与我的券）。
 *
 * <p>抢券链路（设计第四节）：限流 → Lua 原子扣减 Redis 库存与限领 Hash → 落
 * {@code platform_coupon_user}（唯一键 DB 兜底）。DB 落库失败必须补偿回滚 Redis，
 * 否则库存会凭空少一张。
 *
 * <p>本轮只做领取，不接下单抵扣（设计第八节），所以券的状态不会被下单修改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformCouponService {

    /**
     * 券列表缓存 TTL：设计 60s，取 54s 给 JsonCache 的 +0~10% 抖动留余量（实际 TTL 不超过 60s）。
     */
    private static final Duration COUPONS_TTL = Duration.ofSeconds(54);
    private static final TypeReference<List<CouponVO>> COUPONS_TYPE = new TypeReference<>() {
    };
    private static final int STATUS_ENABLED = 1;
    private static final int COUPON_UNUSED = 1;

    private static final String MSG_SUCCESS = "领取成功";
    private static final String MSG_SOLD_OUT = "手慢了，券已领完";
    private static final String MSG_NOT_WARMED = "活动尚未预热，请稍后重试";
    private static final String MSG_RATE_LIMITED = "操作过于频繁，请 5 秒后再试";
    private static final String MSG_EXPIRED = "活动未开始或已结束";

    private final PlatformCouponMapper couponMapper;
    private final PlatformCouponUserMapper couponUserMapper;
    private final PlatformCacheService cacheService;
    private final JsonCache jsonCache;

    // ---------------------------------------------------------------- 公开读

    /** 可领券列表（设计 3.2）：模板走 60s 缓存，leftStock 实时读 Redis，claimed 按当前用户查 DB */
    public List<CouponVO> listPublic(Long campusId, Long userId) {
        Long keyCampus = campusId == null ? 0L : campusId;
        List<CouponVO> list = jsonCache.getOrLoad(PlatformCacheKeys.coupons(keyCampus), COUPONS_TTL,
                COUPONS_TYPE, () -> loadTemplates(campusId));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Long> couponIds = list.stream().map(CouponVO::getId).toList();
        Set<Long> claimedIds = userId == null ? Set.of() : claimedIds(userId, couponIds);
        LocalDateTime now = LocalDateTime.now();
        for (CouponVO vo : list) {
            int left = cacheService.couponLeftStock(vo.getId());
            if (left < 0) {
                // Redis 未预热 / 不可用：回退 DB 计数，保证页面不显示 -1
                left = dbLeftStock(vo.getId(), vo.getTotalStock());
            }
            boolean claimed = claimedIds.contains(vo.getId());
            vo.setLeftStock(left);
            vo.setClaimed(claimed);
            vo.setClaimable(isActive(vo.getStatus(), vo.getStartAt(), vo.getEndAt(), now) && left > 0 && !claimed);
        }
        return list;
    }

    // ---------------------------------------------------------------- 抢券

    /** 抢券（设计 3.3）：1 成功 / 0 领完 / -1 未预热 / -2 超限 / -3 未开始或已结束 / -4 限流 */
    @Transactional(rollbackFor = Exception.class)
    public CouponClaimVO claim(Long couponId, Long userId) {
        if (couponId == null || userId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少优惠券或用户");
        }
        PlatformCoupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || Objects.equals(coupon.getStatus(), 0)) {
            throw new BizException(ResultCode.PARAM_ERROR, "优惠券不存在或已停用");
        }
        // 0) 已经领过：直接按"超限"返回，且不消耗限流次数。
        //    顺序原因：验收口径要求"重复领取 => data.code=-2"；若先限流，5 秒内重复点会
        //    先被 -4 拦下，用户看到的就不是"已领过"而是"操作频繁"（QA A4-7）。
        //    DB 唯一键 (coupon_id,user_id) 是权威判据，Redis Hash 只是加速。
        if (alreadyClaimed(couponId, userId)) {
            return CouponClaimVO.of(PlatformCacheService.CODE_LIMIT, limitMessage(perUserLimit(coupon)),
                    currentLeft(coupon));
        }
        // 1) 限流：5 秒 1 次
        if (!cacheService.tryRateLimit(PlatformCacheKeys.rateClaim(userId))) {
            return CouponClaimVO.of(PlatformCacheService.CODE_RATE_LIMITED, MSG_RATE_LIMITED, currentLeft(coupon));
        }
        LocalDateTime now = LocalDateTime.now();
        // 2) 时间窗外的请求不用碰 Redis，直接告诉用户
        if (!isActive(coupon.getStatus(), coupon.getStartAt(), coupon.getEndAt(), now)) {
            return CouponClaimVO.of(PlatformCacheService.CODE_EXPIRED, MSG_EXPIRED, currentLeft(coupon));
        }
        int limit = perUserLimit(coupon);

        // 3) Lua 原子扣减（库存 + 限领）
        long result = cacheService.claimCoupon(couponId, userId, limit);
        if (result == PlatformCacheService.CODE_NOT_WARMED) {
            return CouponClaimVO.of(PlatformCacheService.CODE_NOT_WARMED, MSG_NOT_WARMED, currentLeft(coupon));
        }
        if (result == PlatformCacheService.CODE_SOLD_OUT) {
            return CouponClaimVO.of(PlatformCacheService.CODE_SOLD_OUT, MSG_SOLD_OUT, 0);
        }
        if (result == PlatformCacheService.CODE_LIMIT) {
            return CouponClaimVO.of(PlatformCacheService.CODE_LIMIT, limitMessage(limit), currentLeft(coupon));
        }

        // 4) DB 落库：唯一键 (coupon_id,user_id) 是最终兜底
        try {
            PlatformCouponUser record = new PlatformCouponUser();
            record.setCouponId(couponId);
            record.setUserId(userId);
            record.setStatus(COUPON_UNUSED);
            record.setReceivedAt(LocalDateTime.now());
            couponUserMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 Redis 已经判过限领，这里只可能是极端竞态/预热数据不一致 → 回滚 Redis 并提示超限
            cacheService.rollbackCoupon(couponId, userId);
            log.warn("[platform][claim] 唯一键兜底命中重复领取 coupon={} user={}，已回滚 Redis", couponId, userId);
            return CouponClaimVO.of(PlatformCacheService.CODE_LIMIT, limitMessage(limit), currentLeft(coupon));
        } catch (RuntimeException e) {
            // 落库失败必须补偿 Redis，否则"没领到但库存少一张"（设计 3.3）
            cacheService.rollbackCoupon(couponId, userId);
            log.error("[platform][claim] 落库失败已回滚 Redis coupon={} user={}", couponId, userId, e);
            throw e;
        }

        int left = Math.max(cacheService.couponLeftStock(couponId), 0);
        if (left < 0) {
            left = dbLeftStock(couponId, coupon.getTotalStock());
        }
        log.info("[platform][claim] 领券成功 coupon={} user={} left={}", couponId, userId, left);
        return CouponClaimVO.of(PlatformCacheService.CODE_SUCCESS, MSG_SUCCESS, left);
    }

    // ---------------------------------------------------------------- 我的券

    /** 我的券（设计 3.3）：DB 为准，含券模板信息与状态 */
    public List<MyCouponVO> myCoupons(Long userId) {
        return couponUserMapper.selectMyCoupons(userId);
    }

    // ================================================================
    // 第 3 轮 · 运营台券管理（设计 3.3 / 3.8）
    // ================================================================

    /** 运营台券列表：该校区全部券（含停用），带已领数与实时剩余 */
    public List<AdminCouponVO> adminList(Long campusId) {
        Long effective = campusId == null ? DEFAULT_CAMPUS_ID : campusId;
        List<PlatformCoupon> coupons = couponMapper.selectList(Wrappers.<PlatformCoupon>lambdaQuery()
                .eq(PlatformCoupon::getCampusId, effective)
                .orderByDesc(PlatformCoupon::getId));
        List<AdminCouponVO> result = new ArrayList<>(coupons.size());
        for (PlatformCoupon coupon : coupons) {
            result.add(toAdminVO(coupon));
        }
        return result;
    }

    /** 新建券：写 DB 后只重建该券 Redis 库存 + 失效券列表缓存（不整库重建） */
    @Transactional(rollbackFor = Exception.class)
    public AdminCouponVO create(CouponSaveDTO dto) {
        PlatformCoupon coupon = new PlatformCoupon();
        applyCouponFields(coupon, dto, true);
        couponMapper.insert(coupon);
        Long id = coupon.getId();
        Long campusId = coupon.getCampusId();
        TxHooks.afterCommit(() -> {
            cacheService.evictCouponList(campusId);
            cacheService.rebuildCoupon(id);
        });
        log.info("[platform][coupon][create][{}] campus={} name={} type={} stock={}",
                id, campusId, coupon.getName(), coupon.getType(), coupon.getTotalStock());
        return toAdminVOWithDbLeft(couponMapper.selectById(id));
    }

    /** 编辑券：已发放数 > 新库存时拒绝；保存后只重建该券 Redis 库存 + 失效券列表缓存 */
    @Transactional(rollbackFor = Exception.class)
    public AdminCouponVO update(Long id, CouponSaveDTO dto) {
        PlatformCoupon existing = couponMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "优惠券不存在");
        }
        long claimed = countClaimed(id);
        if (dto.getTotalStock() != null && claimed > dto.getTotalStock()) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "已发放 " + claimed + " 张，总库存不能小于已发放数");
        }
        Long oldCampusId = existing.getCampusId();
        PlatformCoupon update = new PlatformCoupon();
        update.setId(id);
        applyCouponFields(update, dto, false);
        couponMapper.updateById(update);
        Long campusId = update.getCampusId() != null ? update.getCampusId() : oldCampusId;
        TxHooks.afterCommit(() -> {
            cacheService.evictCouponList(campusId);
            if (!Objects.equals(oldCampusId, campusId)) {
                cacheService.evictCouponList(oldCampusId);
            }
            cacheService.rebuildCoupon(id);
        });
        log.info("[platform][coupon][update][{}] campus={} claimed={} totalStock={}",
                id, campusId, claimed, dto.getTotalStock());
        return toAdminVOWithDbLeft(couponMapper.selectById(id));
    }

    /** 券启停：status=0|1；保存后只重建该券 Redis 库存 + 失效券列表缓存 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != STATUS_ENABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "券状态只能是 0（停用）或 1（启用）");
        }
        PlatformCoupon existing = couponMapper.selectById(id);
        if (existing == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "优惠券不存在");
        }
        PlatformCoupon update = new PlatformCoupon();
        update.setId(id);
        update.setStatus(status);
        couponMapper.updateById(update);
        Long campusId = existing.getCampusId();
        TxHooks.afterCommit(() -> {
            cacheService.evictCouponList(campusId);
            cacheService.rebuildCoupon(id);
        });
        log.info("[platform][coupon][status][{}] {} -> {}", id, existing.getStatus(), status);
    }

    // ---------------------------------------------------------------- 运营台内部

    /** 默认校区（设计：campusId 缺省按 2 处理） */
    private static final long DEFAULT_CAMPUS_ID = 2L;

    private AdminCouponVO toAdminVO(PlatformCoupon coupon) {
        AdminCouponVO vo = new AdminCouponVO();
        vo.setId(coupon.getId());
        vo.setName(coupon.getName());
        vo.setType(coupon.getType());
        vo.setThresholdAmount(coupon.getThresholdAmount());
        vo.setDiscountAmount(coupon.getDiscountAmount());
        vo.setDiscountRate(coupon.getDiscountRate());
        vo.setTotalStock(coupon.getTotalStock() == null ? 0 : coupon.getTotalStock());
        vo.setPerUserLimit(perUserLimit(coupon));
        vo.setStartAt(coupon.getStartAt());
        vo.setEndAt(coupon.getEndAt());
        vo.setStatus(coupon.getStatus());
        vo.setStatusText(Objects.equals(coupon.getStatus(), STATUS_ENABLED) ? "启用" : "停用");
        vo.setCampusId(coupon.getCampusId());
        long claimed = countClaimed(coupon.getId());
        vo.setClaimedCount(claimed);
        int left = cacheService.couponLeftStock(coupon.getId());
        if (left < 0) {
            left = (int) Math.max(vo.getTotalStock() - claimed, 0);
        }
        vo.setLeftStock(left);
        return vo;
    }

    private long countClaimed(Long couponId) {
        Long count = couponUserMapper.selectCount(Wrappers.<PlatformCouponUser>lambdaQuery()
                .eq(PlatformCouponUser::getCouponId, couponId));
        return count == null ? 0 : count;
    }

    /**
     * 写操作响应里的剩余库存用 DB 口径（total-claimed）：
     * Redis 单券重建是在事务提交后（{@code TxHooks.afterCommit}）才执行的，
     * 如果响应直接读 Redis 会读到重建前的旧值（例如把总库存从 10 改到 8 却回显 10-claimed）。
     */
    private AdminCouponVO toAdminVOWithDbLeft(PlatformCoupon coupon) {
        AdminCouponVO vo = toAdminVO(coupon);
        vo.setTotalStock(coupon.getTotalStock() == null ? 0 : coupon.getTotalStock());
        vo.setLeftStock((int) Math.max(vo.getTotalStock() - vo.getClaimedCount(), 0));
        return vo;
    }

    /**
     * 券字段归一 + 校验（新建/编辑共用）。
     * 时间用 {@link PlatformTimeUtils} 兼容前端多种格式；折扣率同时兼容 0.9 与 90。
     */
    private void applyCouponFields(PlatformCoupon coupon, CouponSaveDTO dto, boolean create) {
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "券名称不能为空");
        }
        coupon.setName(dto.getName().trim());
        if (dto.getCampusId() != null) {
            coupon.setCampusId(dto.getCampusId());
        } else if (create) {
            coupon.setCampusId(DEFAULT_CAMPUS_ID);
        }
        Integer type = dto.getType();
        if (type == null || (type != 1 && type != 2)) {
            throw new BizException(ResultCode.PARAM_ERROR, "券类型只能是 1（满减）或 2（折扣）");
        }
        coupon.setType(type);
        LocalDateTime startAt = PlatformTimeUtils.parse(dto.getStartAt());
        LocalDateTime endAt = PlatformTimeUtils.parse(dto.getEndAt());
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new BizException(ResultCode.PARAM_ERROR, "开始时间必须早于结束时间");
        }
        coupon.setStartAt(startAt);
        coupon.setEndAt(endAt);
        coupon.setThresholdAmount(dto.getThresholdAmount());
        if (type == 1) {
            if (dto.getThresholdAmount() == null || dto.getThresholdAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "满减券必须填写满减门槛（>=0）");
            }
            if (dto.getDiscountAmount() == null || dto.getDiscountAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(ResultCode.PARAM_ERROR, "满减券的减免金额必须大于 0");
            }
            coupon.setDiscountAmount(dto.getDiscountAmount());
        } else {
            if (dto.getDiscountRate() == null) {
                throw new BizException(ResultCode.PARAM_ERROR, "折扣券必须填写折扣率");
            }
            coupon.setDiscountRate(normalizeRate(dto.getDiscountRate()));
        }
        if (dto.getTotalStock() == null || dto.getTotalStock() < 1) {
            throw new BizException(ResultCode.PARAM_ERROR, "总库存至少 1");
        }
        coupon.setTotalStock(dto.getTotalStock());
        coupon.setPerUserLimit(dto.getPerUserLimit() == null || dto.getPerUserLimit() < 1
                ? 1 : dto.getPerUserLimit());
        if (dto.getStatus() != null) {
            if (dto.getStatus() != 0 && dto.getStatus() != STATUS_ENABLED) {
                throw new BizException(ResultCode.PARAM_ERROR, "券状态只能是 0（停用）或 1（启用）");
            }
            coupon.setStatus(dto.getStatus());
        } else if (create) {
            coupon.setStatus(STATUS_ENABLED);
        }
    }

    /**
     * 折扣率归一：兼容前端三种写法 —— {@code 0.9}（9 折）、{@code 9}（9 折）、{@code 90}（9 折）、
     * {@code 85}（8.5 折）。统一转成 DB DECIMAL(4,2) 的 0~1 口径。
     */
    private BigDecimal normalizeRate(BigDecimal raw) {
        BigDecimal rate = raw;
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            // >10 视为百分比（90/85），1~10 视为"几折"（9/8.5）
            rate = rate.compareTo(BigDecimal.TEN) >= 0
                    ? rate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                    : rate.divide(BigDecimal.TEN, 4, RoundingMode.HALF_UP);
        }
        if (rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "折扣率必须在 0~1 之间（如 0.9 表示 9 折）");
        }
        return rate.setScale(2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- 内部

    private List<CouponVO> loadTemplates(Long campusId) {
        LocalDateTime now = LocalDateTime.now();
        List<PlatformCoupon> coupons = couponMapper.selectList(Wrappers.<PlatformCoupon>lambdaQuery()
                .eq(campusId != null, PlatformCoupon::getCampusId, campusId)
                .eq(PlatformCoupon::getStatus, STATUS_ENABLED)
                .le(PlatformCoupon::getStartAt, now)
                .ge(PlatformCoupon::getEndAt, now)
                .orderByAsc(PlatformCoupon::getId));
        return coupons.stream().map(this::toVO).toList();
    }

    private CouponVO toVO(PlatformCoupon coupon) {
        CouponVO vo = new CouponVO();
        vo.setId(coupon.getId());
        vo.setName(coupon.getName());
        vo.setType(coupon.getType());
        vo.setThresholdAmount(coupon.getThresholdAmount());
        vo.setDiscountAmount(coupon.getDiscountAmount());
        vo.setDiscountRate(coupon.getDiscountRate());
        vo.setPerUserLimit(coupon.getPerUserLimit());
        vo.setStartAt(coupon.getStartAt());
        vo.setEndAt(coupon.getEndAt());
        vo.setStatus(coupon.getStatus());
        vo.setTotalStock(coupon.getTotalStock() == null ? 0 : coupon.getTotalStock());
        vo.setLeftStock(0);
        vo.setClaimed(false);
        vo.setClaimable(false);
        return vo;
    }

    private Set<Long> claimedIds(Long userId, List<Long> couponIds) {
        if (couponIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> ids = new HashSet<>();
        for (PlatformCouponUser record : couponUserMapper.selectList(Wrappers.<PlatformCouponUser>lambdaQuery()
                .eq(PlatformCouponUser::getUserId, userId)
                .in(PlatformCouponUser::getCouponId, couponIds))) {
            ids.add(record.getCouponId());
        }
        return ids;
    }

    /** 该用户是否已领过这张券（DB 唯一键口径） */
    private boolean alreadyClaimed(Long couponId, Long userId) {
        return couponUserMapper.selectCount(Wrappers.<PlatformCouponUser>lambdaQuery()
                .eq(PlatformCouponUser::getCouponId, couponId)
                .eq(PlatformCouponUser::getUserId, userId)) > 0;
    }

    private boolean isActive(Integer status, LocalDateTime startAt, LocalDateTime endAt, LocalDateTime now) {
        if (status == null || status != STATUS_ENABLED) {
            return false;
        }
        if (startAt != null && now.isBefore(startAt)) {
            return false;
        }
        return endAt == null || !now.isAfter(endAt);
    }

    private int perUserLimit(PlatformCoupon coupon) {
        return coupon.getPerUserLimit() == null || coupon.getPerUserLimit() < 1 ? 1 : coupon.getPerUserLimit();
    }

    private String limitMessage(int limit) {
        return "该券每人限领 " + limit + " 张";
    }

    private int currentLeft(PlatformCoupon coupon) {
        int left = cacheService.couponLeftStock(coupon.getId());
        return left >= 0 ? left : dbLeftStock(coupon.getId(), coupon.getTotalStock());
    }

    /** DB 口径剩余库存：total_stock - 已领记录数（Redis 未预热时的兜底） */
    private int dbLeftStock(Long couponId, Integer totalStock) {
        long used = couponUserMapper.selectCount(Wrappers.<PlatformCouponUser>lambdaQuery()
                .eq(PlatformCouponUser::getCouponId, couponId));
        return (int) Math.max((totalStock == null ? 0 : totalStock) - used, 0);
    }
}
