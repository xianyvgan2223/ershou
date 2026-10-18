package com.ershou.campus.module.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.module.platform.config.PlatformCacheKeys;
import com.ershou.campus.module.platform.entity.PlatformConsignPoint;
import com.ershou.campus.module.platform.entity.PlatformCoupon;
import com.ershou.campus.module.platform.entity.PlatformCouponUser;
import com.ershou.campus.module.platform.entity.PlatformFlashSale;
import com.ershou.campus.module.platform.entity.PlatformFlashSaleItem;
import com.ershou.campus.module.platform.entity.PlatformSeckillRecord;
import com.ershou.campus.module.platform.mapper.PlatformConsignPointMapper;
import com.ershou.campus.module.platform.mapper.PlatformCouponMapper;
import com.ershou.campus.module.platform.mapper.PlatformCouponUserMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleItemMapper;
import com.ershou.campus.module.platform.mapper.PlatformFlashSaleMapper;
import com.ershou.campus.module.platform.mapper.PlatformSeckillRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 平台代卖 Redis 中枢：限流、Lua 原子扣减、库存读取、预热/重建。
 *
 * <p>为什么把这些集中在一个 service：
 * <ul>
 *   <li>券和秒杀的"原子扣减"逻辑完全一样，只是 key 不同，放一起只需维护两份 Lua + 一组方法；</li>
 *   <li>预热/补偿/读库存都直接操作 Redis 与 Mapper，不反向依赖业务 Service，避免循环依赖；</li>
 *   <li>设计第四节所有 key 的读写都经过这里，出问题时只看一个类。</li>
 * </ul>
 *
 * <p><b>正确性口径</b>：Redis 是"库存的并发闸门"，DB（{@code platform_coupon_user} /
 * {@code platform_seckill_record}）是"领取/成交的真值"。Redis 丢了、没过期、被重建都只影响
 * 抢的瞬间，预热时永远按 DB 重算（设计第四节），所以丢 key 不会导致数据错乱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformCacheService {

    /** Lua / 业务统一返回码（设计 3.3） */
    public static final int CODE_SUCCESS = 1;
    public static final int CODE_SOLD_OUT = 0;
    public static final int CODE_NOT_WARMED = -1;
    public static final int CODE_LIMIT = -2;
    public static final int CODE_EXPIRED = -3;
    public static final int CODE_RATE_LIMITED = -4;

    /** 预热分布式锁 TTL：预热很快，30s 足够；进程被杀锁也会自己过期 */
    private static final Duration WARMUP_LOCK_TTL = Duration.ofSeconds(30);
    /** 抢券 / 抢购限流窗口：5 秒 1 次（设计第四节） */
    private static final Duration RATE_WINDOW = Duration.ofSeconds(5);
    private static final int RATE_MAX_TIMES = 1;
    /** 库存 key 最短 TTL：券/场次已过期时也留 1h，避免刚过期就被重建出负数 */
    private static final Duration MIN_STOCK_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final PlatformConsignPointMapper pointMapper;
    private final PlatformCouponMapper couponMapper;
    private final PlatformCouponUserMapper couponUserMapper;
    private final PlatformFlashSaleMapper flashSaleMapper;
    private final PlatformFlashSaleItemMapper flashSaleItemMapper;
    private final PlatformSeckillRecordMapper seckillRecordMapper;

    /** 抢券 Lua：stock==nil → -1；stock<=0 → 0；超限 → -2；否则 DECR + HINCRBY → 1 */
    private final DefaultRedisScript<Long> couponClaimScript = loadScript("lua/coupon_claim.lua");

    /** 抢购 Lua：与抢券同一套判定，独立文件便于两边各自演进 */
    private final DefaultRedisScript<Long> seckillGrabScript = loadScript("lua/seckill_grab.lua");

    /**
     * 从 classpath 加载 Lua 脚本（设计第四节：{@code ClassPathResource} + {@code DefaultRedisScript}）。
     * 当前 Spring Data Redis 版本没有 {@code (Resource, Class)} 构造器，用 setLocation + setResultType。
     */
    private static DefaultRedisScript<Long> loadScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType(Long.class);
        return script;
    }

    // ---------------------------------------------------------------- 限流

    /**
     * INCR + EXPIRE 5 秒：窗口内只放行 1 次（设计第四节）。
     *
     * <p>Redis 异常时选择放行：限流是"防刷"不是"正确性"，缓存故障不该把正常用户挡在门外。
     */
    public boolean tryRateLimit(String key) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, RATE_WINDOW);
            }
            return count != null && count <= RATE_MAX_TIMES;
        } catch (Exception e) {
            log.warn("[platform][rate] 限流读写失败，本次放行 key={}：{}", key, e.getMessage());
            return true;
        }
    }

    // ------------------------------------------------------- Lua 原子扣减

    /** 抢券：返回 1 成功 / 0 领完 / -1 未预热 / -2 超限（异常也按未预热返回，让前端提示稍后重试） */
    public long claimCoupon(Long couponId, Long userId, int perUserLimit) {
        try {
            Long r = redis.execute(couponClaimScript,
                    List.of(PlatformCacheKeys.couponStock(couponId), PlatformCacheKeys.couponUsers(couponId)),
                    String.valueOf(userId), String.valueOf(perUserLimit));
            return r == null ? CODE_NOT_WARMED : r;
        } catch (Exception e) {
            log.warn("[platform][claim] Lua 执行失败 coupon={} user={}：{}", couponId, userId, e.getMessage());
            return CODE_NOT_WARMED;
        }
    }

    /** 抢购：返回码同抢券 */
    public long grabSeckill(Long itemId, Long userId, int perUserLimit) {
        try {
            Long r = redis.execute(seckillGrabScript,
                    List.of(PlatformCacheKeys.seckillStock(itemId), PlatformCacheKeys.seckillUsers(itemId)),
                    String.valueOf(userId), String.valueOf(perUserLimit));
            return r == null ? CODE_NOT_WARMED : r;
        } catch (Exception e) {
            log.warn("[platform][grab] Lua 执行失败 item={} user={}：{}", itemId, userId, e.getMessage());
            return CODE_NOT_WARMED;
        }
    }

    /**
     * 抢券补偿：DB 落库失败（唯一键兜底）时把 Lua 扣掉的库存与已领数加回去。
     * 设计 3.3 要求"建单失败必须补偿回滚 Redis（INCR + HINCRBY -1）"，券同理。
     */
    public void rollbackCoupon(Long couponId, Long userId) {
        rollback(PlatformCacheKeys.couponStock(couponId), PlatformCacheKeys.couponUsers(couponId), userId, "claim");
    }

    /** 抢购补偿：建单失败时把 Lua 扣掉的库存与已购数加回去 */
    public void rollbackSeckill(Long itemId, Long userId) {
        rollback(PlatformCacheKeys.seckillStock(itemId), PlatformCacheKeys.seckillUsers(itemId), userId, "grab");
    }

    private void rollback(String stockKey, String usersKey, Long userId, String scene) {
        try {
            redis.opsForValue().increment(stockKey);
            redis.opsForHash().increment(usersKey, String.valueOf(userId), -1);
            log.info("[platform][{}] 已补偿回滚 Redis user={} stockKey={}", scene, userId, stockKey);
        } catch (Exception e) {
            log.warn("[platform][{}] 补偿回滚失败（下次预热按 DB 重算会自愈）user={}：{}", scene, userId, e.getMessage());
        }
    }

    // ------------------------------------------------------------ 库存读取

    /** 券剩余库存；未预热 / Redis 异常返回 -1（调用方回退 DB 计数） */
    public int couponLeftStock(Long couponId) {
        return readStock(PlatformCacheKeys.couponStock(couponId));
    }

    /** 秒杀剩余库存；未预热 / Redis 异常返回 -1 */
    public int seckillLeftStock(Long itemId) {
        return readStock(PlatformCacheKeys.seckillStock(itemId));
    }

    private int readStock(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            return raw == null ? -1 : Integer.parseInt(raw);
        } catch (Exception e) {
            log.warn("[platform][stock] 读取库存失败 key={}：{}", key, e.getMessage());
            return -1;
        }
    }

    // ---------------------------------------------------------- 缓存失效

    /** 代卖点增删改：删该校区的点位列表 + 概览缓存 */
    public void evictPointCaches(Long campusId) {
        if (campusId == null) {
            return;
        }
        deleteKeys(List.of(PlatformCacheKeys.points(campusId), PlatformCacheKeys.overview(campusId)));
    }

    /** 平台商品发布 / 下架：概览里的在售数变了 */
    public void evictOverview(Long campusId) {
        if (campusId == null) {
            return;
        }
        deleteKeys(List.of(PlatformCacheKeys.overview(campusId)));
    }

    /** 券列表缓存失效（第 3 轮设计 3.8：券增改/启停后调用，不整库重建） */
    public void evictCouponList(Long campusId) {
        if (campusId == null) {
            return;
        }
        deleteKeys(List.of(PlatformCacheKeys.coupons(campusId), PlatformCacheKeys.overview(campusId)));
    }

    /** 秒杀场次缓存失效（第 3 轮设计 3.8：场次写后调用，不整库重建） */
    public void evictFlashList(Long campusId) {
        if (campusId == null) {
            return;
        }
        deleteKeys(List.of(PlatformCacheKeys.flash(campusId), PlatformCacheKeys.overview(campusId)));
    }

    /** 秒杀商品从场次移除：删掉它的库存/用户 hash key，避免孤儿 key 占着库存 */
    public void evictSeckillItem(Long itemId) {
        if (itemId == null) {
            return;
        }
        deleteKeys(List.of(PlatformCacheKeys.seckillStock(itemId), PlatformCacheKeys.seckillUsers(itemId)));
    }

    private void deleteKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            redis.delete(keys);
        } catch (Exception e) {
            log.warn("[platform][cache] 删除缓存失败，等 TTL 自然过期 keys={}：{}", keys.size(), e.getMessage());
        }
    }

    // -------------------------------------------------------- 预热 / 重建

    /**
     * 预热（设计第四节）：拿 {@code SET NX EX} 锁，拿不到就跳过（多实例只有一个实例真正预热）。
     *
     * @param force true=强制重建（先删 stock/users key 再按 DB 重算，供后台刷新接口使用）；
     *              false=只补缺失的 key（启动预热，不覆盖正在进行的抢购状态）
     * @return 本次写入的库存 key 数（券 + 秒杀）
     */
    public int warmup(boolean force) {
        String lockKey = PlatformCacheKeys.warmupLock();
        String token = UUID.randomUUID().toString();
        if (!tryWarmupLock(lockKey, token)) {
            log.info("[platform][warmup] 其它实例正在预热，跳过本轮");
            return 0;
        }
        try {
            int coupons = warmupCoupons(force);
            int seckills = warmupSeckills(force);
            log.info("[platform][warmup] 完成 force={} 券库存 key={} 秒杀库存 key={}", force, coupons, seckills);
            return coupons + seckills;
        } catch (Exception e) {
            log.warn("[platform][warmup] 预热失败（不影响启动，可调后台刷新接口重试）：{}", e.getMessage());
            return 0;
        } finally {
            unlockWarmup(lockKey, token);
        }
    }

    /**
     * 后台强制重建（设计 3.4）：先删列表缓存与库存 key，再按 DB 真值重新预热。
     *
     * <p>注意：本方法只负责"删缓存 + 重建库存 key"；4 类业务缓存（points/coupons/overview/flash）
     * 由 {@code PlatformAdminController#refreshCache} 在读穿透后重新写回，
     * 避免在缓存服务里重复实现 VO 组装。
     *
     * @return 重建的库存 key 数
     */
    public int refresh() {
        for (Long campusId : knownCampusIds()) {
            deleteKeys(List.of(PlatformCacheKeys.points(campusId), PlatformCacheKeys.coupons(campusId),
                    PlatformCacheKeys.overview(campusId), PlatformCacheKeys.flash(campusId)));
        }
        return warmup(true);
    }

    /** 当前有平台数据的校区 id 集合（后台刷新时按校区重建缓存用） */
    public Set<Long> knownCampusIds() {
        Set<Long> campusIds = new LinkedHashSet<>();
        try {
            couponMapper.selectList(Wrappers.<PlatformCoupon>lambdaQuery()
                            .select(PlatformCoupon::getCampusId))
                    .forEach(c -> addCampus(campusIds, c.getCampusId()));
            flashSaleMapper.selectList(Wrappers.<PlatformFlashSale>lambdaQuery()
                            .select(PlatformFlashSale::getCampusId))
                    .forEach(f -> addCampus(campusIds, f.getCampusId()));
            pointMapper.selectList(Wrappers.<PlatformConsignPoint>lambdaQuery()
                            .select(PlatformConsignPoint::getCampusId))
                    .forEach(p -> addCampus(campusIds, p.getCampusId()));
        } catch (Exception e) {
            log.warn("[platform][refresh] 收集校区失败：{}", e.getMessage());
        }
        return campusIds;
    }

    private void addCampus(Set<Long> campusIds, Long campusId) {
        if (campusId != null) {
            campusIds.add(campusId);
        }
    }

    // -------------------------------------------- 单券 / 单场次重建（设计 3.8）

    /**
     * 只重建一张券的 Redis 库存与 users hash（不整库重建）。
     *
     * <p>口径与启动预热完全一致：{@code leftStock = total_stock - COUNT(platform_coupon_user)}，
     * users hash 按 DB 领取记录整体回填，TTL 到 end_at+1h。
     * 券被停用/过期时也照写（领取链路会先校验 DB 状态，不会真的领到），
     * 这样重新启用后无需再等预热。
     */
    public void rebuildCoupon(Long couponId) {
        if (couponId == null) {
            return;
        }
        try {
            PlatformCoupon coupon = couponMapper.selectById(couponId);
            if (coupon == null) {
                return;
            }
            List<PlatformCouponUser> claims = couponUserMapper.selectList(Wrappers.<PlatformCouponUser>lambdaQuery()
                    .eq(PlatformCouponUser::getCouponId, couponId));
            int total = coupon.getTotalStock() == null ? 0 : coupon.getTotalStock();
            int left = Math.max(total - claims.size(), 0);
            Duration ttl = ttlUntil(coupon.getEndAt());
            writeStock(PlatformCacheKeys.couponStock(couponId), left, ttl);
            rebuildUserHash(PlatformCacheKeys.couponUsers(couponId),
                    claims.stream().map(PlatformCouponUser::getUserId).toList(), ttl);
            log.info("[platform][cache] 单券缓存已重建 coupon={} left={}", couponId, left);
        } catch (Exception e) {
            log.warn("[platform][cache] 单券缓存重建失败（不影响 DB，下次预热会自愈）coupon={}：{}",
                    couponId, e.getMessage());
        }
    }

    /**
     * 只重建一个秒杀场次的 Redis 库存与 users hash（不整库重建）。
     *
     * <p>口径：{@code leftStock = seckill_stock - COUNT(platform_seckill_record)}，TTL 到 end_at+1h。
     */
    public void rebuildFlashSale(Long sceneId) {
        if (sceneId == null) {
            return;
        }
        try {
            PlatformFlashSale scene = flashSaleMapper.selectById(sceneId);
            if (scene == null) {
                return;
            }
            List<PlatformFlashSaleItem> items = flashSaleItemMapper.selectList(
                    Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                            .eq(PlatformFlashSaleItem::getFlashSaleId, sceneId)
                            .orderByAsc(PlatformFlashSaleItem::getSort)
                            .orderByAsc(PlatformFlashSaleItem::getId));
            Duration ttl = ttlUntil(scene.getEndAt());
            for (PlatformFlashSaleItem item : items) {
                List<PlatformSeckillRecord> records = seckillRecordMapper.selectList(
                        Wrappers.<PlatformSeckillRecord>lambdaQuery()
                                .eq(PlatformSeckillRecord::getItemId, item.getItemId()));
                int stock = item.getSeckillStock() == null ? 0 : item.getSeckillStock();
                int left = Math.max(stock - records.size(), 0);
                writeStock(PlatformCacheKeys.seckillStock(item.getItemId()), left, ttl);
                rebuildUserHash(PlatformCacheKeys.seckillUsers(item.getItemId()),
                        records.stream().map(PlatformSeckillRecord::getUserId).toList(), ttl);
            }
            log.info("[platform][cache] 单场次缓存已重建 scene={} items={}", sceneId, items.size());
        } catch (Exception e) {
            log.warn("[platform][cache] 单场次缓存重建失败（不影响 DB，下次预热会自愈）scene={}：{}",
                    sceneId, e.getMessage());
        }
    }

    private boolean tryWarmupLock(String lockKey, String token) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, WARMUP_LOCK_TTL));
        } catch (Exception e) {
            log.warn("[platform][warmup] 加锁失败，按拿不到锁处理：{}", e.getMessage());
            return false;
        }
    }

    private void unlockWarmup(String lockKey, String token) {
        try {
            String current = redis.opsForValue().get(lockKey);
            if (token.equals(current)) {
                redis.delete(lockKey);
            }
        } catch (Exception e) {
            log.warn("[platform][warmup] 释放锁失败（30s 后自动过期）：{}", e.getMessage());
        }
    }

    /** 券：leftStock = total_stock - COUNT(platform_coupon_user)，同时按 DB 回填用户已领 Hash */
    private int warmupCoupons(boolean force) {
        List<PlatformCoupon> coupons = couponMapper.selectList(Wrappers.<PlatformCoupon>lambdaQuery()
                .eq(PlatformCoupon::getStatus, 1)
                .gt(PlatformCoupon::getEndAt, LocalDateTime.now())
                .orderByAsc(PlatformCoupon::getId));
        int written = 0;
        for (PlatformCoupon coupon : coupons) {
            String stockKey = PlatformCacheKeys.couponStock(coupon.getId());
            String usersKey = PlatformCacheKeys.couponUsers(coupon.getId());
            if (!force && hasKey(stockKey)) {
                continue;
            }
            List<PlatformCouponUser> claims = couponUserMapper.selectList(Wrappers.<PlatformCouponUser>lambdaQuery()
                    .eq(PlatformCouponUser::getCouponId, coupon.getId()));
            int total = coupon.getTotalStock() == null ? 0 : coupon.getTotalStock();
            int left = Math.max(total - claims.size(), 0);
            Duration ttl = ttlUntil(coupon.getEndAt());
            writeStock(stockKey, left, ttl);
            rebuildUserHash(usersKey, claims.stream().map(PlatformCouponUser::getUserId).toList(), ttl);
            written++;
        }
        return written;
    }

    /** 秒杀：leftStock = seckill_stock - COUNT(platform_seckill_record)，同样回填已购 Hash */
    private int warmupSeckills(boolean force) {
        List<PlatformFlashSale> scenes = flashSaleMapper.selectList(Wrappers.<PlatformFlashSale>lambdaQuery()
                .eq(PlatformFlashSale::getStatus, 2)
                .gt(PlatformFlashSale::getEndAt, LocalDateTime.now())
                .orderByAsc(PlatformFlashSale::getId));
        int written = 0;
        for (PlatformFlashSale scene : scenes) {
            List<PlatformFlashSaleItem> items = flashSaleItemMapper.selectList(
                    Wrappers.<PlatformFlashSaleItem>lambdaQuery()
                            .eq(PlatformFlashSaleItem::getFlashSaleId, scene.getId())
                            .orderByAsc(PlatformFlashSaleItem::getSort)
                            .orderByAsc(PlatformFlashSaleItem::getId));
            for (PlatformFlashSaleItem item : items) {
                String stockKey = PlatformCacheKeys.seckillStock(item.getItemId());
                String usersKey = PlatformCacheKeys.seckillUsers(item.getItemId());
                if (!force && hasKey(stockKey)) {
                    continue;
                }
                List<PlatformSeckillRecord> records = seckillRecordMapper.selectList(
                        Wrappers.<PlatformSeckillRecord>lambdaQuery()
                                .eq(PlatformSeckillRecord::getItemId, item.getItemId()));
                int stock = item.getSeckillStock() == null ? 0 : item.getSeckillStock();
                int left = Math.max(stock - records.size(), 0);
                Duration ttl = ttlUntil(scene.getEndAt());
                writeStock(stockKey, left, ttl);
                rebuildUserHash(usersKey, records.stream().map(PlatformSeckillRecord::getUserId).toList(), ttl);
                written++;
            }
        }
        return written;
    }

    private void writeStock(String key, int left, Duration ttl) {
        redis.opsForValue().set(key, String.valueOf(Math.max(left, 0)));
        redis.expire(key, ttl);
    }

    /** 按 DB 记录整体重建 Hash：field=userId，value=该用户已领/已购次数 */
    private void rebuildUserHash(String usersKey, List<Long> userIds, Duration ttl) {
        redis.delete(usersKey);
        if (userIds.isEmpty()) {
            return;
        }
        Map<String, String> counts = new HashMap<>();
        for (Map.Entry<Long, Long> e : userIds.stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting())).entrySet()) {
            counts.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        redis.opsForHash().putAll(usersKey, counts);
        redis.expire(usersKey, ttl);
    }

    private boolean hasKey(String key) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key));
        } catch (Exception e) {
            log.warn("[platform][warmup] 查询 key 失败，按不存在处理 key={}：{}", key, e.getMessage());
            return false;
        }
    }

    /** TTL 到 end_at + 1h（设计第四节）；已过期时给最短 1h，避免马上被写负库存逻辑打穿 */
    private Duration ttlUntil(LocalDateTime endAt) {
        if (endAt == null) {
            return MIN_STOCK_TTL;
        }
        Duration ttl = Duration.between(LocalDateTime.now(), endAt.plusHours(1));
        return ttl.isNegative() || ttl.isZero() ? MIN_STOCK_TTL : ttl;
    }
}
