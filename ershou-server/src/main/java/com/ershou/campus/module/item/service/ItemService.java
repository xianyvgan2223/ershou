package com.ershou.campus.module.item.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.cache.CacheProperties;
import com.ershou.campus.common.cache.DictionaryCache;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.admin.entity.ItemReview;
import com.ershou.campus.module.admin.service.ItemReviewService;
import com.ershou.campus.module.cart.entity.Favorite;
import com.ershou.campus.module.cart.mapper.FavoriteMapper;
import com.ershou.campus.module.item.dto.ItemPublishDTO;
import com.ershou.campus.module.item.dto.ItemUpdateDTO;
import com.ershou.campus.module.item.dto.ItemQueryDTO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.vo.ItemVO;
import com.ershou.campus.module.item.vo.SellerVO;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.search.config.SearchProperties;
import com.ershou.campus.module.search.dto.ItemSearchHits;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.ershou.campus.module.search.service.ItemSearchService;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    private static final TypeReference<ItemVO> ITEM_VO = new TypeReference<>() {
    };

    private final ItemMapper itemMapper;
    private final FavoriteMapper favoriteMapper;
    private final UserService userService;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ItemSearchService itemSearchService;
    private final ItemIndexService itemIndexService;
    private final SearchProperties searchProperties;
    private final JsonCache cache;
    private final CacheEvictor evictor;
    private final DictionaryCache dict;
    private final CacheProperties cacheProps;
    /** 商品下架时顺手收掉挂在该商品上的约面交单 */
    private final OrderService orderService;

    /**
     * 上架审核登记（运营后台「上架审核」页的队列）。
     *
     * <p>为什么用字段注入 + {@code @Lazy} 而不是构造器注入：{@code ItemReviewService} 自己也要
     * 用 {@code ItemMapper}/{@code OrderService}/{@code ItemService.statusText}，
     * 与本类构成 bean 环。启动期用一个代理把这根环剪断，运行时调用没有任何差别。
     */
    @org.springframework.context.annotation.Lazy
    @org.springframework.beans.factory.annotation.Autowired
    private ItemReviewService itemReviewService;

    /**
     * 商品有效期（天）。到期后由定时任务置为「已过期」并下架，卖家可一键重新上架。
     *
     * <p>历史坑：这里原来是 {@code DEFAULT_HOURS_VALID = 60 * 24}，名字是"小时"、
     * 用的却是 {@code plusMinutes(...)} —— 商品实际只挂 <b>24 小时</b>，
     * 而文档一直写着 60 天。单位写错 + 没有测试覆盖，挂了很久没人发现。
     */
    @Value("${ershou.item.valid-days:7}")
    private int validDays = 7;

    /** 商品可挂的天数（详情 / 列表的倒计时与文案都用它，避免前端再写一份魔法数字） */
    public int getValidDays() {
        return validDays;
    }

    /**
     * 商品限时总开关（开发/演示期可关闭，默认 {@code true} = 原来的 7 天限时行为）。
     *
     * <p>关闭（{@code false}）时只影响两件事：
     * <ul>
     *   <li>发布 / 重新上架**不写过期时间**（{@code expire_at = NULL}，等同永久有效）；</li>
     *   <li>到期任务 {@code ScheduledTasks#expireItems()} 直接跳过，不做任何到期下架。</li>
     * </ul>
     * 审核、发布限额、锁库、状态机等其它逻辑一律不受影响。
     */
    @Value("${ershou.item.validity-enabled:true}")
    private boolean validityEnabled = true;

    /** 商品限时是否启用（给定时任务 {@code ScheduledTasks#expireItems()} 判断是否跳过） */
    public boolean isValidityEnabled() {
        return validityEnabled;
    }

    public PageResult<ItemVO> page(ItemQueryDTO q, Long userId) {
        PageResult<ItemVO> result = queryPage(q);
        fillFavorited(result.getRecords(), userId);
        return result;
    }

    /** 关键词检索优先走 Elasticsearch；ES 不可用/异常时按配置降级 MySQL LIKE */
    private PageResult<ItemVO> queryPage(ItemQueryDTO q) {
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
        // 先按 ES 顺序挑出仍可展示的，再批量组装：分类/点位走字典缓存 MGET，卖家只查 1 次
        List<Item> ordered = new ArrayList<>(hits.ids().size());
        for (Long id : hits.ids()) {
            Item item = items.get(id);
            if (item == null || item.getStatus() == null || item.getStatus() != 2) {
                continue;
            }
            ordered.add(item);
        }
        List<ItemVO> records = toVOList(ordered);
        for (ItemVO vo : records) {
            ItemSearchHits.Highlight highlight = hits.highlights().get(vo.getId());
            if (highlight != null) {
                vo.setHighlightTitle(highlight.title());
                vo.setHighlightDescription(highlight.description());
            }
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
        if (q.getPreferPointId() != null) {
            w.eq(Item::getPreferPointId, q.getPreferPointId());
        }
        // 合并点位：地图上"N"个近距离点位合成一个气泡，点开要看**组内全部**商品。
        //
        // ⚠️ 2026-10-11 修：以前只按代表点的 id 查，于是气泡上写着"3 件"、
        // 小窗里却只有 1 件（用户报的就是这个）。这里让接口支持一次查一组点位。
        // 与 preferPointId 同时传时以集合为准（前者是单点场景的简写）。
        if (q.getPreferPointIds() != null && !q.getPreferPointIds().isEmpty()) {
            // 上限 50：组本来只有几条，设个上限免得这个参数被拿来当"全表扫描"的口子。
            // 超了不报错，截断即可 —— 地图小窗最多显示十几条，多出来的查了也没用。
            List<Long> ids = q.getPreferPointIds().stream().filter(Objects::nonNull).distinct().limit(50).toList();
            if (!ids.isEmpty()) {
                w.in(Item::getPreferPointId, ids);
            }
        }
        switch (q.getSort() == null ? "latest" : q.getSort()) {
            case "price_asc" -> w.orderByAsc(Item::getPrice);
            case "price_desc" -> w.orderByDesc(Item::getPrice);
            default -> w.orderByDesc(Item::getPublishedAt).orderByDesc(Item::getId);
        }
        Page<Item> page = itemMapper.selectPage(new Page<>(q.getPageNum(), Math.min(q.getPageSize(), 100)), w);
        return PageResult.of(toVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<ItemVO> mine(long pageNum, long pageSize, Long userId) {
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery()
                .eq(Item::getSellerId, userId)
                .orderByDesc(Item::getId);
        Page<Item> page = itemMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)), w);
        PageResult<ItemVO> result = PageResult.of(toVOList(page.getRecords()),
                page.getTotal(), page.getCurrent(), page.getSize());
        fillFavorited(result.getRecords(), userId);
        return result;
    }

    /**
     * 商品详情：先读 Redis 缓存（含空值缓存防穿透），未命中才回源 DB 并回填。
     * 浏览量不再同步 UPDATE（热点行锁 + 每次都刷 updated_at 会连累 ES 增量同步），
     * 改为 Redis 计数、定时批量回写（见 ScheduledTasks.flushViewCounts）。
     *
     * <p><b>为什么要走 {@code getOrLoad} 而不是手工 get/set（2026-10-10 改）</b>：
     * 这是全站最热的读接口，缓存一失效就是一次**典型击穿**。
     * 旧实现是"未命中 → 直接查库 → 回填"，**没有任何互斥**：
     * 实测删掉缓存后 50 并发同时打，产生约 50 次回源查询、每个请求都走慢路径、P95 ≈ 500ms。
     * 走 getOrLoad 后由重建锁保证**只有一个请求回源**，其余等待并读缓存。
     *
     * <p>两层 key 的分工保持不变：
     * <ul>
     *   <li>{@code item:detail:{id}} —— 正常数据；</li>
     *   <li>{@code item:null:{id}} —— 空值哨兵。它的作用是**先于 getOrLoad 拦截**：
     *       如果一个不存在的 id 已经被标记过，就直接抛"不存在"，连重建锁都不用抢
     *       （否则每个不存在的 id 都要抢一次锁，反而更重）。</li>
     * </ul>
     * loader 里遇到"库里也没有"时**自己写空值哨兵**再抛业务异常 —— getOrLoad 会原样抛出去，
     * 但哨兵已经落下，后续请求在第 2 步就被拦住，不会真的把重建锁占满 5 秒。
     */
    public ItemVO detail(Long id, Long userId) {
        String detailKey = RedisKeys.itemDetail(id);
        String nullKey = RedisKeys.itemNull(id);
        // 空值缓存命中 → 直接按"不存在"处理，不查库（防穿透的第一道，也是最便宜的一道）
        if (cache.isNullCached(nullKey)) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }

        ItemVO vo = cache.getOrLoad(detailKey, cacheProps.getItemDetailTtl(), ITEM_VO, () -> {
            Item item = itemMapper.selectById(id);
            if (item == null) {
                cache.setNull(nullKey, cacheProps.getNullValueTtl());
                throw new BizException(ResultCode.ITEM_NOT_FOUND);
            }
            ItemVO fresh = toVO(item);
            fresh.setDescription(item.getDescription());
            return fresh;
        });

        // ⚠️ 下面两处是**就地修改** vo。命中缓存时 vo 是本次反序列化出来的副本，
        // 且这两行之后**不再回写缓存**，所以不会污染 Redis。
        // 谁要是以后在这里加了 `cache.set(...)` 回写，就会把"某个用户的 favorited=true"
        // 和"叠了浏览量的 viewCount"写进共享缓存，变成所有人可见的脏数据 —— 千万别加。
        int pending = pendingViews(id);
        if (pending > 0) {
            vo.setViewCount((vo.getViewCount() == null ? 0 : vo.getViewCount()) + pending);
        }
        fillFavorited(List.of(vo), userId);
        return vo;
    }

    /** 浏览量增量先记在 Redis（开发文档 6.2：ershou:item:view:{itemId}），返回当前未回写的增量 */
    private int pendingViews(Long itemId) {
        try {
            String key = RedisKeys.itemView(itemId);
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return 0;
            }
            if (count == 1L) {
                redis.expire(key, Duration.ofHours(2));
                redis.opsForSet().add(RedisKeys.itemViewDirty(), String.valueOf(itemId));
            }
            return count > 0 ? count.intValue() : 0;
        } catch (Exception e) {
            log.warn("[item][view][{}] 浏览量计数失败，本次不计数：{}", itemId, e.getMessage());
            return 0;
        }
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

        // 关键词机审已移除：发布直接进入在售(2)，由运营「上架审核」页事后确认
        int status = 2;

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
        // 个人发布恒为个人卖家（1）。显式赋值而不是吃 DB 默认值：INSERT 受全局 field-strategy 影响，
        // 一旦哪天改成 IGNORED/ALWAYS，null 会以显式 NULL 写入 NOT NULL 列直接报错（见迁移 seller_type）。
        // 平台商品不经这里——由 platform 模块直插 seller_type=2（本方法不接收 sellerType 入参）。
        item.setSellerType(1);
        item.setStatus(status);
        // 限时关闭时不写过期时间（expire_at 为 NULL = 永久有效）；INSERT 对 null 字段走 NOT_NULL
        // 策略会直接省略该列，DB 默认值本来就是 NULL，所以这里不需要额外处理。
        item.setExpireAt(validityEnabled ? LocalDateTime.now().plusDays(validDays) : null);
        item.setPublishedAt(LocalDateTime.now());
        item.setViewCount(0);
        item.setChatCount(0);
        item.setFavCount(0);
        item.setVersion(0);
        // 上架快照先落成当前内容：有快照才谈得上"之后改没改过"
        item.setLastLiveTitle(item.getTitle());
        item.setLastLiveDescHash(ItemReviewService.hash(item.getDescription()));
        itemMapper.insert(item);
        TxHooks.afterCommit(() -> itemIndexService.syncById(item.getId()));
        TxHooks.afterCommit(() -> evictor.itemChanged(item.getId()));
        // 机审通过 → 直接就在售。此时登记进运营的「上架审核」页，
        // 由审核员事后确认（不拦截发布，只是让每条上架都有人看过）。
        if (status == 2) {
            itemReviewService.onItemLiveOptimistic(item.getId(), ItemReview.TYPE_NEW);
        }

        log.info("[item][publish][{}][{}] status={}", item.getId(), userId, status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", item.getId());
        data.put("status", status);
        data.put("statusText", statusText(status));
        return data;
    }

    /**
     * 编辑自己发布的商品（标题 / 价格 / 描述 / 图片…）。
     *
     * <p>规则：
     * <ul>
     *   <li>只能改自己的商品；已售出的不能改（留着交易记录）；
     *   <li>改了标题或描述就**重新过一遍机审**：命中禁售词直接拒绝、可疑词把商品打回「审核中」；
     *   <li>原本是「审核中」的商品，这次审核通过就自动上架；
     *   <li>提交事务后同步 ES 索引 + 清 Redis 详情缓存，保证列表/详情立刻看到新值。
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(Long itemId, ItemUpdateDTO dto, Long userId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (!Objects.equals(item.getSellerId(), userId)) {
            throw new BizException(ResultCode.ITEM_NOT_OWNER);
        }
        if (item.getStatus() != null && item.getStatus() == 4) {
            throw new BizException(ResultCode.ITEM_SOLD, "商品已售出，不能再改价格和标题");
        }

        Item update = new Item();
        update.setId(itemId);

        String newTitle = StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : item.getTitle();
        String newDescription = dto.getDescription() != null ? dto.getDescription() : item.getDescription();
        boolean textChanged = !Objects.equals(newTitle, item.getTitle())
                || !Objects.equals(newDescription, item.getDescription());

        Integer newStatus = item.getStatus();
        if (textChanged) {
            // 关键词机审已移除：内容修改不改状态，由运营「上架审核」页事后确认
            update.setAuditResult(1);
        }

        if (StringUtils.hasText(dto.getTitle())) {
            update.setTitle(newTitle);
        }
        if (dto.getDescription() != null) {
            update.setDescription(newDescription);
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
        if (dto.getNegotiable() != null) {
            update.setNegotiable(dto.getNegotiable());
        }
        if (dto.getStock() != null) {
            update.setStock(dto.getStock());
        }
        if (dto.getIsUnique() != null) {
            update.setIsUnique(dto.getIsUnique());
        }
        if (dto.getImages() != null && !dto.getImages().isEmpty()) {
            update.setImages(writeJson(dto.getImages()));
            update.setCoverUrl(dto.getImages().get(0));
        }
        if (dto.getPreferPointId() != null) {
            update.setPreferPointId(dto.getPreferPointId());
        }
        if (dto.getVideoUrl() != null) {
            update.setVideoUrl(dto.getVideoUrl());
        }

        itemMapper.updateById(update);
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
        // 在售期间改了标题/描述 → 内容变了等于新内容，重新回到运营的「上架审核」页。
        // 只改价格/库存/图片不算（审核员确认的是"这条内容挂出来合不合适"，与价格无关），
        // 所以把 textChanged 直接传下去，不在这里重新判断。
        itemReviewService.onContentMaybeChanged(itemId, textChanged);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", itemId);
        data.put("status", newStatus);
        data.put("statusText", statusText(newStatus));
        log.info("[item][update][{}][{}] textChanged={} status={}", itemId, userId, textChanged, newStatus);
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
        // 商品已下架：挂在上面的约面交意向与待面交单一起收掉，否则买家那边会一直挂着
        // 「待面交」和一个永不消失的红点（尤其商品原本已锁库时，锁再也等不到释放）
        int closed = orderService.closeActiveOrders(itemId, "商品已被卖家下架，本次约面交自动作废");
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
        log.info("[item][offline][{}][{}] 同时作废 {} 张挂单", itemId, userId, closed);
    }

    /**
     * 一键重新上架：把「已下架(5)」或「已过期(7)」的商品重新挂 {@code validDays} 天。
     *
     * <p>只放开这两种状态，其余各有各的出口：
     * <ul>
     *   <li>4 已售出 —— 成交记录要留着，不能再卖</li>
     *   <li>6 审核未通过 —— 要走「编辑 → 重新过机审」，而不是直接复活</li>
     *   <li>8 违规下架 —— 运营的处置，卖家不能自己解除</li>
     * </ul>
     *
     * <p>不重新过机审（内容一个字没改，之前已经放行），也不占「每日发布上限」的额度 ——
     * 这不是新内容，只是把旧内容重新挂出来。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> relist(Long itemId, Long userId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (!Objects.equals(item.getSellerId(), userId)) {
            throw new BizException(ResultCode.ITEM_NOT_OWNER);
        }
        Integer status = item.getStatus();
        if (status == null || (status != 5 && status != 7)) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "只有「已下架」或「已过期」的商品可以重新上架");
        }
        LocalDateTime now = LocalDateTime.now();
        // 限时关闭时不写过期时间（NULL = 永久有效）。这里必须用 wrapper 的 set 显式写 NULL：
        // 实体字段走 MyBatis-Plus 的 NOT_NULL 策略，set(null) 会被从 SET 子句里省略，清不掉旧值。
        LocalDateTime expireAt = validityEnabled ? now.plusDays(validDays) : null;
        Item update = new Item();
        update.setStatus(2);
        // 重新上架算一次新的曝光：列表按 publishedAt 排序，不刷新的话它会一直埋在最后
        update.setPublishedAt(now);
        // 条件更新兜并发：两个标签页同时点，只会生效一次
        int rows = itemMapper.update(update, Wrappers.<Item>lambdaUpdate()
                .eq(Item::getId, itemId)
                .in(Item::getStatus, 5, 7)
                .set(Item::getExpireAt, expireAt));
        if (rows == 0) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品状态已变化，请刷新后重试");
        }
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
        // 重新上架 = 又一次上架，重新进「上架审核」页。
        // 走 relist 的只有「已下架(5)/已过期(7)」——卖家自己撤下来或自然到期的内容，
        // 不代表没问题（可能是他改了名之后再挂出来），所以仍然要让审核员看一眼。
        itemReviewService.onItemLiveOptimistic(itemId, ItemReview.TYPE_RELIST);
        log.info("[item][relist][{}][{}] 从 status={} 重新上架，有效期至 {}", itemId, userId, status,
                expireAt == null ? "永久（限时已关闭）" : expireAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", itemId);
        data.put("status", 2);
        data.put("statusText", statusText(2));
        data.put("expireAt", expireAt);
        return data;
    }

    /**
     * 违规商品「二次申请上架」。
     *
     * <p>卖家视角：被判定违规下架(8) 之后，商品自己不能解除（{@link #relist} 明确拒绝 8），
     * 唯一出口是改好内容后点这里提交申请。
     *
     * <p>提交后商品进入「审核中(1)」而<b>不是直接重新在售</b> —— 这正是产品口径里
     * "只有被违规商品二次申请上架要审核"的那一条：在审核员通过之前，它对买家不可见、
     * 也搜不到。审核员在审核工作台点通过（→ 在售）或驳回（→ 审核未通过）。
     *
     * @throws BizException 非本人商品 / 商品不是"违规下架"状态
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyRelist(Long itemId, Long userId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (!Objects.equals(item.getSellerId(), userId)) {
            throw new BizException(ResultCode.ITEM_NOT_OWNER);
        }
        if (item.getStatus() == null || item.getStatus() != 8) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR,
                    "只有被违规下架的商品需要申请上架；已下架/已过期请直接点「重新上架」");
        }
        int rows = itemMapper.applyRelist(itemId);
        if (rows == 0) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品状态已变化，请刷新后重试");
        }
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
        // 提交通知运营：审核工作台里立刻能看到这条待审（实时推送 + 待办数字）
        itemReviewService.notifyViolationRelistApplied(itemId, item.getTitle());
        log.info("[item][apply-relist][{}][{}] 违规商品二次申请上架，已转入审核中", itemId, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("itemId", itemId);
        data.put("status", 1);
        data.put("statusText", statusText(1));
        return data;
    }

    public Item getOnSale(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return item;
    }

    /** 批量回填「当前登录用户是否已收藏」，未登录或空列表直接跳过，避免多余查询 */
    public void fillFavorited(List<ItemVO> records, Long userId) {
        if (userId == null || records == null || records.isEmpty()) {
            return;
        }
        List<Long> itemIds = records.stream()
                .map(ItemVO::getId).filter(Objects::nonNull).distinct().toList();
        if (itemIds.isEmpty()) {
            return;
        }
        Set<Long> favoritedIds = favoriteMapper.selectList(Wrappers.<Favorite>lambdaQuery()
                        .eq(Favorite::getUserId, userId)
                        .in(Favorite::getItemId, itemIds))
                .stream().map(Favorite::getItemId).collect(Collectors.toSet());
        for (ItemVO vo : records) {
            vo.setFavorited(favoritedIds.contains(vo.getId()));
        }
    }

    /** 单条组装：分类 / 点位走字典缓存（命中即 0 次 DB 查询） */
    public ItemVO toVO(Item item) {
        Map<Long, String> categoryNames = item.getCategoryId() == null ? Map.of()
                : Collections.singletonMap(item.getCategoryId(), dict.categoryName(item.getCategoryId()));
        Map<Long, TradePoint> points = item.getPreferPointId() == null ? Map.of()
                : Collections.singletonMap(item.getPreferPointId(), dict.point(item.getPreferPointId()));
        Map<Long, SysUser> sellers = userService.mapByIds(
                item.getSellerId() == null ? List.of() : List.of(item.getSellerId()));
        return assemble(item, categoryNames, points, sellers);
    }

    /**
     * 批量组装（列表 / 搜索 / 收藏页）：分类与点位一次 MGET，卖家一次 selectBatchIds。
     * 之前每条商品各查一次分类 + 点位 + 卖家，20 条列表 = 60 次多余查询。
     */
    public List<ItemVO> toVOList(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<Long, String> categoryNames = dict.categoryNames(
                items.stream().map(Item::getCategoryId).filter(Objects::nonNull).toList());
        Map<Long, TradePoint> points = dict.points(
                items.stream().map(Item::getPreferPointId).filter(Objects::nonNull).toList());
        Map<Long, SysUser> sellers = userService.mapByIds(
                items.stream().map(Item::getSellerId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<ItemVO> records = items.stream().map(item -> assemble(item, categoryNames, points, sellers)).toList();
        applyPendingViews(records);
        return records;
    }

    /**
     * 叠加 Redis 里尚未回写 DB 的浏览量增量（每 10 分钟才批量回写一次），
     * 保证首页 / 搜索 / 收藏列表和详情页看到的是同一个数字——否则列表最多滞后 10 分钟。
     * 一次 MGET，20 条列表的成本可以忽略；Redis 异常时退化为"展示已回写值"。
     */
    private void applyPendingViews(List<ItemVO> records) {
        List<Long> ids = records.stream().map(ItemVO::getId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        List<String> raw;
        try {
            raw = redis.opsForValue().multiGet(ids.stream().map(RedisKeys::itemView).toList());
        } catch (Exception e) {
            log.warn("[item][view] 读取未回写浏览量失败，改展示已回写值：{}", e.getMessage());
            return;
        }
        if (raw == null) {
            return;
        }
        Map<Long, Integer> pending = new HashMap<>(ids.size());
        for (int i = 0; i < ids.size() && i < raw.size(); i++) {
            Integer delta = parsePositiveInt(raw.get(i));
            if (delta != null) {
                pending.put(ids.get(i), delta);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        for (ItemVO vo : records) {
            Integer delta = pending.get(vo.getId());
            if (delta != null) {
                vo.setViewCount((vo.getViewCount() == null ? 0 : vo.getViewCount()) + delta);
            }
        }
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ItemVO assemble(Item item, Map<Long, String> categoryNames,
                            Map<Long, TradePoint> points, Map<Long, SysUser> sellers) {
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
        vo.setExpireAt(item.getExpireAt());
        // 平台代卖标注：seller_type 由 platform 模块直插为 2，个人商品恒为 1
        // （迁移列为 NOT NULL DEFAULT 1，这里再兜一层 null → 1，避免老数据/异常数据让前端认成"未知卖家"）。
        // 列表 / 搜索 / 我的发布 / 详情都走 assemble()，所以只在这一处填即可覆盖全部读路径。
        vo.setSellerType(item.getSellerType() == null ? 1 : item.getSellerType());
        vo.setPlatformPointId(item.getPlatformPointId());
        // TODO(platform): 点位名称属 platform 模块，item 模块不反向依赖它；只透出 id，由前端解析名称。
        // （若改成在这里取名，需引入 platform 的只读查询，注意别形成 item → platform → search 的环。）
        if (item.getCategoryId() != null) {
            vo.setCategoryName(categoryNames.get(item.getCategoryId()));
        }
        if (item.getPreferPointId() != null) {
            TradePoint p = points.get(item.getPreferPointId());
            if (p != null) {
                vo.setPreferPointName(p.getName());
                vo.setPreferPointGuide(p.getGuideText());
            }
        }
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
