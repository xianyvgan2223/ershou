package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.cache.CacheEvictor;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.admin.config.ReviewProperties;
import com.ershou.campus.module.admin.dto.BatchReviewActionDTO;
import com.ershou.campus.module.admin.entity.ItemReview;
import com.ershou.campus.module.admin.mapper.ItemReviewMapper;
import com.ershou.campus.module.admin.vo.BatchAuditResultVO;
import com.ershou.campus.module.admin.vo.ItemReviewStatsVO;
import com.ershou.campus.module.admin.vo.ItemReviewVO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.entity.ItemCategory;
import com.ershou.campus.module.item.mapper.ItemCategoryMapper;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.ershou.campus.module.search.service.ItemIndexService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 上架审核：让审核员确认"每一条上架到在售的商品都被人工看过"。
 *
 * <h3>产品口径（2026-10-02 定）</h3>
 * <ol>
 *   <li><b>正常上架不拦截</b>：发布成功即在售、立刻可搜可买，和以前完全一样。
 *       本服务只负责"登记进审核页"，<b>从不</b>把商品卡在待审状态里；</li>
 *   <li>每条上架都进「上架审核」页 → 审核员点「已审核」→ 从该页消失（归档留痕）；</li>
 *   <li>在售期间<b>改名 / 改描述</b>要重新回到该页（内容变了，等于新内容）；
 *       检测靠 {@code item.last_live_title} / {@code last_live_desc_hash} 两个快照列 —
 *       它们记的是"审核员已经看过的那一版"，与当前内容对不上就说明改过；</li>
 *   <li><b>违规下架(8) 的商品二次申请上架必须审核</b>：卖家申请后商品停在「审核中(1)」，
 *       审核员在审核工作台通过才回到在售（走的是既有的 {@code AdminItemService.approve}）。</li>
 * </ol>
 *
 * <h3>为什么入队失败绝不能影响发布</h3>
 * 入队只是"登记"，发布才是用户真正在做的事。任何入队异常都吞掉并记日志，
 * 绝不能让卖家看到"发布失败"——那是最坏的结果（内容没发出去，审核页也没有）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemReviewService {

    private static final int MAX_PAGE_SIZE = 100;
    /** 商品当前状态处于"审核员还能处置"的范围：审核中/在售/已锁定/审核驳回 */
    private static final List<Integer> ACTIONABLE_STATUS = List.of(1, 2, 3, 6);
    /**
     * "还挂在平台上"的商品：在售(2) / 已锁定(3)。
     * 审核页只关心这些 —— 已售出、已下架、已过期、违规下架、审核中的都不在这个页面里。
     */
    private static final String LIVE_ITEM_SQL = "SELECT id FROM item WHERE status IN (2, 3)";
    private static final String DEFAULT_OFFLINE_REASON = "上架审核：内容不符合规范，已下架";

    private final ItemReviewMapper reviewMapper;
    private final ItemMapper itemMapper;
    private final ItemCategoryMapper categoryMapper;
    private final TradePointMapper pointMapper;
    private final CampusMapper campusMapper;
    private final UserService userService;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;
    private final OrderService orderService;
    private final ReviewPushService pushService;
    private final AuditLogService auditLogService;
    private final ReviewProperties props;
    private final ObjectMapper json;

    /* ==================================================================
     *  一、写入路径（被 ItemService / AdminItemService 调用）
     * ================================================================== */

    /**
     * 商品进入在售的登记入口：写"审核员已看过的那一版"快照 + 入队。
     *
     * <p>调用点：发布（status=2 时）、审核通过（1→2）、到期/下架后重新上架、存量修复任务。
     * <b>不要</b>在商品离开在售时调用（下架/售出/过期都不算上架）。
     */
    public void onItemLiveOptimistic(Long itemId, int reviewType) {
        try {
            Item item = itemMapper.selectById(itemId);
            if (item == null) {
                return;
            }
            enqueue(item, reviewType, null);
        } catch (Exception e) {
            // 登记失败不影响发布/审核本身，只记日志
            log.warn("[review][enqueue][{}] 入队失败（不影响商品状态）：{}", itemId, e.getMessage());
        }
    }

    /**
     * 在售期间内容变更的登记：改名/改描述后重新回到审核页。
     *
     * <p>用 {@code rowContentChanged} 而不是简单地"有更新就入队"：
     * 改个价格、动一下库存不该让审核员再确认一遍内容。传 null 表示"我不确定，按快照判断"。
     */
    public void onContentMaybeChanged(Long itemId, Boolean rowContentChanged) {
        try {
            Item item = itemMapper.selectById(itemId);
            if (item == null || item.getStatus() == null || item.getStatus() != 2) {
                // 不在售的商品不需要"重新确认在售内容"
                return;
            }
            boolean changed = rowContentChanged != null ? rowContentChanged : contentChanged(item);
            if (!changed) {
                return;
            }
            // 传"改之前"的标题作为快照：审核员要看的是"从什么改成了什么"，
            // 而不是把新标题再抄一遍（那就等于没有对比信息）
            enqueue(item, ItemReview.TYPE_RENAMED, null, item.getLastLiveTitle());
            log.info("[review][renamed][{}] 在售期间内容变更，重新进入上架审核页", itemId);
        } catch (Exception e) {
            log.warn("[review][renamed][{}] 内容变更登记失败（不影响商品本身）：{}", itemId, e.getMessage());
        }
    }

    /** 入队/重新入队的统一实现：一个商品只保留一条记录，重新入队时累加 revision */
    private void enqueue(Item item, int reviewType, String note) {
        enqueue(item, reviewType, note, null);
    }

    /**
     * @param titleSnapshot 改名场景下"改之前"的标题（审核员要看到 it changed FROM what）。
     *                      不传则用当前标题 —— 新上架时两者本来就一样。
     *                      <b>必须在 {@code markLiveSnapshot} 覆盖快照列之前取</b>，
     *                      所以调用方传的是 {@code item.getLastLiveTitle()} 的旧值。
     */
    private void enqueue(Item item, int reviewType, String note, String titleSnapshot) {
        LocalDateTime now = LocalDateTime.now();
        // 无论哪种入口，都先把"审核员已看过的那一版"更新成当前内容：
        // 这样下次改名时快照对不上，能被 onContentMaybeChanged 认出来。
        itemMapper.markLiveSnapshot(item.getId(), item.getTitle(), hash(item.getDescription()));

        ItemReview existing = selectByItemId(item.getId());
        ItemReview row = new ItemReview();
        row.setItemId(item.getId());
        row.setSellerId(item.getSellerId());
        row.setTitle(StringUtils.hasText(titleSnapshot) ? titleSnapshot : item.getTitle());
        row.setReviewType(reviewType);
        row.setStatus(ItemReview.STATUS_PENDING);
        row.setQueuedAt(now);
        row.setNote(note);
        row.setReviewAction(null);
        row.setReviewedAt(null);
        row.setReviewerId(null);
        row.setClaimedAt(null);
        row.setContentChangedAt(reviewType == ItemReview.TYPE_RENAMED ? now : null);
        row.setFirstQueuedAt(existing != null && existing.getFirstQueuedAt() != null
                ? existing.getFirstQueuedAt()
                : (existing != null ? existing.getQueuedAt() : now));
        row.setRevision(existing == null || existing.getRevision() == null ? 1 : existing.getRevision() + 1);

        if (existing == null) {
            reviewMapper.insert(row);
        } else {
            row.setId(existing.getId());
            // 只有改名才更新"内容变更时间"；其它类型保留历史值（那一列会被上面的 null 覆盖，这里补回）
            if (reviewType != ItemReview.TYPE_RENAMED) {
                row.setContentChangedAt(existing.getContentChangedAt());
            }
            reviewMapper.updateById(row);
        }
        String typeText = reviewTypeText(reviewType);
        log.info("[review][enqueue][{}] type={}({}) revision={} pending={}",
                item.getId(), reviewType, typeText, row.getRevision(), pendingCount());

        // 事务提交后再推送：读接口走的是另一个连接，事务没提交时会推出一条"点进去看不到"的假消息
        TxHooks.afterCommit(() -> broadcastQueued(item.getId(), typeText));
    }

    /** 事务提交后推送"新条目"。pending 每次都现算，避免多个事件乱序让前端数字倒退 */
    private void broadcastQueued(Long itemId, String typeText) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pending", pendingCount());
            data.put("itemId", itemId);
            data.put("reviewTypeText", typeText);
            data.put("onlineReviewers", pushService.onlineReviewerCount());
            pushService.broadcast("queued", data);
        } catch (Exception e) {
            log.debug("[review][push] queued 推送失败：{}", e.getMessage());
        }
    }

    /* ==================================================================
     *  二、审核员操作：已审核 / 下架
     * ================================================================== */

    /**
     * 「已审核」：确认看过且没问题 → 从待审列表消失（归档）。
     *
     * <p>用条件更新（{@code WHERE status = 0}）兜并发：两个审核员同时点，
     * 只有一个生效，另一个收到 {@code REVIEW_ALREADY_HANDLED}，
     * 前端把那条直接移出列表并提示"已被其他审核员处理"，不会出现两人都以为是自己处置的。
     *
     * @return 处置后的统计（新的一页数字，前端不用为此再打一次接口）
     */
    @Transactional(rollbackFor = Exception.class)
    public ItemReviewStatsVO markReviewed(Long itemId, String note, Long operatorId) {
        ItemReview before = requireReview(itemId);
        int rows = reviewMapper.update(null, Wrappers.<ItemReview>lambdaUpdate()
                .eq(ItemReview::getItemId, itemId)
                .eq(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                .set(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .set(ItemReview::getReviewAction, ItemReview.ACTION_REVIEWED)
                .set(ItemReview::getReviewerId, operatorId)
                .set(ItemReview::getReviewedAt, LocalDateTime.now())
                .set(ItemReview::getNote, StringUtils.hasText(note) ? trim(note, 200) : null));
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_ALREADY_HANDLED);
        }
        String action = "review-item-reviewed";
        auditLogService.record(operatorId, "item", action, "item", itemId,
                state("reviewStatus", before.getStatus(), "revision", before.getRevision()),
                state("reviewStatus", ItemReview.STATUS_DONE, "action", ItemReview.ACTION_REVIEWED, "note", note));
        log.info("[review][mark-reviewed][{}][operator={}] revision={}", itemId, operatorId, before.getRevision());
        TxHooks.afterCommit(() -> broadcastHandled(itemId));
        return stats();
    }

    /**
     * 「下架」：审核员看着不对就撤下来。
     *
     * <p>这里下架置为<b>已下架(5)</b>而不是违规下架(8)：够不上违规但也不该继续挂着的内容，
     * 卖家改完可以重新上架（重新上架会再次进审核页）。明确违规的仍走商品管理的「强制下架」。
     */
    @Transactional(rollbackFor = Exception.class)
    public ItemReviewStatsVO downlist(Long itemId, String reason, Long operatorId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        ItemReview before = requireReview(itemId);
        String text = StringUtils.hasText(reason) ? trim(reason, 200) : DEFAULT_OFFLINE_REASON;

        int rows = itemMapper.downlist(itemId);
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_ALREADY_HANDLED);
        }
        // 下架会连带作废挂在上面的面交单/意向单（和卖家自己下架同一套口径），
        // 否则买家那边会一直挂着一个永远等不到的面交单
        int closed = orderService.closeActiveOrders(itemId, "商品已被平台下架，本次约面交自动作废");
        afterItemWrite(itemId);
        reviewMapper.update(null, Wrappers.<ItemReview>lambdaUpdate()
                .eq(ItemReview::getItemId, itemId)
                .set(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .set(ItemReview::getReviewAction, ItemReview.ACTION_OFFLINE)
                .set(ItemReview::getReviewerId, operatorId)
                .set(ItemReview::getReviewedAt, LocalDateTime.now())
                .set(ItemReview::getNote, text));
        auditLogService.record(operatorId, "item", "review-item-offline", "item", itemId,
                state("status", item.getStatus(), "reviewStatus", before.getStatus()),
                state("status", 5, "action", ItemReview.ACTION_OFFLINE, "reason", text));
        log.info("[review][downlist][{}][operator={}] 从 status={} 下架，同时作废 {} 张挂单；原因：{}",
                itemId, operatorId, item.getStatus(), closed, text);
        TxHooks.afterCommit(() -> broadcastHandled(itemId));
        return stats();
    }

    /**
     * 批量处置（一次勾选多条点「已审核」/「下架」）。
     *
     * <p>逐条独立处理、<b>不因单条失败整批回滚</b>（与审核工作台的批量处置同口径）：
     * 50 条里有 1 条已被别人处理，不该让另外 49 条白干。
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchAuditResultVO batch(BatchReviewActionDTO dto, String action, Long operatorId) {
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!"review".equals(act) && !"offline".equals(act)) {
            throw new BizException(ResultCode.PARAM_ERROR, "action 只能是 review 或 offline");
        }
        if ("offline".equals(act) && !StringUtils.hasText(dto.getReason())) {
            throw new BizException(ResultCode.PARAM_ERROR, "批量下架必须填写原因");
        }
        List<Long> ids = dto.getIds() == null ? List.of()
                : dto.getIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "ids 不能为空");
        }
        if (ids.size() > props.getMaxBatchSize()) {
            throw new BizException(ResultCode.PARAM_ERROR, "单次最多处置 " + props.getMaxBatchSize() + " 条");
        }
        int success = 0;
        List<BatchAuditResultVO.Failure> failures = new ArrayList<>();
        for (Long id : ids) {
            try {
                if ("review".equals(act)) {
                    markReviewed(id, dto.getReason(), operatorId);
                } else {
                    downlist(id, dto.getReason(), operatorId);
                }
                success++;
            } catch (BizException e) {
                failures.add(new BatchAuditResultVO.Failure(id, e.getMessage()));
            } catch (Exception e) {
                log.warn("[review][batch-{}][{}] 处置失败：{}", act, id, e.getMessage());
                failures.add(new BatchAuditResultVO.Failure(id, "处理失败：" + e.getMessage()));
            }
        }
        log.info("[review][batch-{}] success={} failed={} operator={}", act, success, failures.size(), operatorId);
        return new BatchAuditResultVO(success, failures.size(), failures);
    }

    /**
     * 卖家提交「违规商品二次申请上架」时通知运营。
     *
     * <p>注意这里<b>不</b>进「上架审核」页：申请后的商品是「审核中(1)」、还没上架，
     * 属于审核工作台（点了通过才走到在售）。推这条事件是为了让运营后台的待办数字
     * 与审核工作台在不需要手动刷新的情况下就更新。
     */
    public void notifyViolationRelistApplied(Long itemId, String title) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pending", pendingCount());
            data.put("itemId", itemId);
            data.put("title", title);
            data.put("pendingAudit", pendingAuditCount());
            TxHooks.afterCommit(() -> pushService.broadcast("violation-applied", data));
        } catch (Exception e) {
            log.warn("[review][violation-apply][{}] 通知失败（不影响申请）：{}", itemId, e.getMessage());
        }
    }

    /** 审核工作台里"审核中(1)"的待审条数（上架审核页顶部顺带显示，两边数字口径不同要分开） */
    public long pendingAuditCount() {
        return itemMapper.selectCount(Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 1));
    }

    /* ==================================================================
     *  三、读路径
     * ================================================================== */

    /**
     * 审核页列表。
     *
     * <p>{@code scope}：
     * <ul>
     *   <li>{@code pending}（默认）待审 + 最近 24 小时内已处置的 —— 审核员处置完还能看到"我刚做了什么"，
     *       不至于点完就消失得无影无踪（点了没反应会被当成页面坏了）；</li>
     *   <li>{@code done} 只看已处置；</li>
     *   <li>{@code all} 全部（含存量导入的归档记录）。</li>
     * </ul>
     *
     * <p><b>「已不在售的不出现在列表里」</b>：这个页面是"检查现在挂在平台上的东西"，
     * 商品已经卖掉了 / 被卖家下架了 / 过期了，就不再是审核员要盯的对象。
     * 关键的是「审核中(1)」也不在这里 —— 那是审核工作台的活（AI 转人工、违规二次申请），
     * 两边的队列各管一段，混在一起审核员根本分不清"这条要不要我点通过"。
     * 反过来，仍留在列表里的记录 {@code actionable=true}，按钮永远是可点的
     * （不会出现"列表里有、但点了报错"的挫败感）。
     */
    public PageResult<ItemReviewVO> page(String scope, Long sellerId, String keyword,
                                         long pageNum, long pageSize) {
        String sc = StringUtils.hasText(scope) ? scope.trim().toLowerCase(Locale.ROOT) : "pending";
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);

        LambdaQueryWrapper<ItemReview> w = Wrappers.<ItemReview>lambdaQuery();
        // 只考虑"还挂在平台上"的商品（在售 2 / 已锁定 3）
        w.inSql(ItemReview::getItemId, LIVE_ITEM_SQL);
        switch (sc) {
            case "done" -> w.eq(ItemReview::getStatus, ItemReview.STATUS_DONE);
            case "all" -> {
                // 不加审核状态条件
            }
            default -> {
                LocalDateTime keepFrom = LocalDateTime.now().minus(props.getDoneKeepWindow());
                w.and(x -> x.eq(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                        .or(y -> y.eq(ItemReview::getStatus, ItemReview.STATUS_DONE)
                                .ge(ItemReview::getReviewedAt, keepFrom)));
            }
        }
        if (sellerId != null) {
            w.eq(ItemReview::getSellerId, sellerId);
        }
        // 「最新上架排最前」。这里刻意<b>不</b>按"先到先审"升序排：
        // 这个页面是"实时看新东西"的巡检台，刚上架的要第一眼看到，
        // 而且实时推送也是把新条目插到顶部 —— 两边顺序不一致的话，
        // 审核员会看到一条商品刚插到顶上、一刷新又跳到底部（像页面在抽风）。
        // "老条目会不会被永远压在下面"由列表里的「等待时长」列和超时标红来解决。
        w.orderByDesc(ItemReview::getQueuedAt).orderByDesc(ItemReview::getId);
        Page<ItemReview> page = reviewMapper.selectPage(new Page<>(num, size), w);

        List<ItemReview> records = page.getRecords();
        List<Long> itemIds = records.stream().map(ItemReview::getItemId).filter(Objects::nonNull).toList();
        Map<Long, Item> items = itemIds.isEmpty() ? Map.of()
                : itemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));

        // 关键词过滤放在内存里：要搜的是"商品当前标题/描述"，而 item_review 里只有入队快照
        List<ItemReview> filtered = records;
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim().toLowerCase(Locale.ROOT);
            filtered = records.stream().filter(r -> {
                Item item = items.get(r.getItemId());
                String title = item != null ? item.getTitle() : r.getTitle();
                String desc = item != null ? item.getDescription() : null;
                return (title != null && title.toLowerCase(Locale.ROOT).contains(kw))
                        || (desc != null && desc.toLowerCase(Locale.ROOT).contains(kw));
            }).toList();
        }

        Map<Long, SysUser> sellers = userService.mapByIds(
                filtered.stream().map(ItemReview::getSellerId).toList());
        Map<Long, SysUser> reviewers = userService.mapByIds(
                filtered.stream().map(ItemReview::getReviewerId).toList());
        Map<Long, ItemCategory> categories = mapBatch(
                filtered.stream().map(r -> item(items, r.getItemId(), Item::getCategoryId)).toList(),
                ids -> categoryMapper.selectBatchIds(ids), ItemCategory::getId);
        Map<Long, TradePoint> points = mapBatch(
                filtered.stream().map(r -> item(items, r.getItemId(), Item::getPreferPointId)).toList(),
                ids -> pointMapper.selectBatchIds(ids), TradePoint::getId);
        Map<Long, Campus> campuses = mapBatch(
                filtered.stream().map(r -> item(items, r.getItemId(), Item::getCampusId)).toList(),
                ids -> campusMapper.selectBatchIds(ids), Campus::getId);
        Map<Long, Integer> rejectCounts = rejectCountsBySeller(
                filtered.stream().map(ItemReview::getSellerId).toList());

        List<ItemReviewVO> vos = filtered.stream()
                .map(r -> toVO(r, items.get(r.getItemId()), sellers, reviewers, categories, points, campuses,
                        rejectCounts))
                .toList();
        long total = StringUtils.hasText(keyword) ? vos.size() : page.getTotal();
        return PageResult.of(vos, total, page.getCurrent(), page.getSize());
    }

    public ItemReviewStatsVO stats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        // 口径与列表完全一致：只算"商品还在售/已锁定"的记录，
        // 否则顶部数字会一直大于列表条数，审核员会以为列表漏了数据
        String liveItems = LIVE_ITEM_SQL;
        long pending = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                .inSql(ItemReview::getItemId, liveItems));
        long queuedToday = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .inSql(ItemReview::getItemId, liveItems)
                .ge(ItemReview::getQueuedAt, todayStart));
        long reviewedToday = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .ge(ItemReview::getReviewedAt, todayStart));
        long onSale = itemMapper.selectCount(Wrappers.<Item>lambdaQuery().in(Item::getStatus, 2, 3));
        // 因为"商品已经不在售"而从列表里隐去的待审数：用来解释"为什么待审数字会自己变小"，
        // 不然审核员会怀疑是自己误点了（其实是被别人卖掉了/下架了）
        long stale = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                .notInSql(ItemReview::getItemId, liveItems));
        ItemReviewStatsVO vo = new ItemReviewStatsVO();
        vo.setPending(pending);
        vo.setQueuedToday(queuedToday);
        vo.setReviewedToday(reviewedToday);
        vo.setOnSaleTotal(onSale);
        vo.setStalePending(stale);
        vo.setOnlineReviewers(pushService.onlineReviewerCount());
        vo.setServerTime(LocalDateTime.now());
        return vo;
    }

    /** 单条详情（前端展开时按需拉，避免列表里带大描述） */
    public ItemReviewVO detail(Long itemId) {
        ItemReview review = requireReview(itemId);
        Item item = itemMapper.selectById(itemId);
        Map<Long, SysUser> sellers = userService.mapByIds(List.of(review.getSellerId()));
        Map<Long, SysUser> reviewers = userService.mapByIds(
                review.getReviewerId() == null ? List.of() : List.of(review.getReviewerId()));
        Map<Long, ItemCategory> categories = item == null || item.getCategoryId() == null ? Map.of()
                : mapBatch(List.of(item.getCategoryId()), ids -> categoryMapper.selectBatchIds(ids), ItemCategory::getId);
        Map<Long, TradePoint> points = item == null || item.getPreferPointId() == null ? Map.of()
                : mapBatch(List.of(item.getPreferPointId()), ids -> pointMapper.selectBatchIds(ids), TradePoint::getId);
        Map<Long, Campus> campuses = item == null || item.getCampusId() == null ? Map.of()
                : mapBatch(List.of(item.getCampusId()), ids -> campusMapper.selectBatchIds(ids), Campus::getId);
        Map<Long, Integer> rejectCounts = rejectCountsBySeller(List.of(review.getSellerId()));
        return toVO(review, item, sellers, reviewers, categories, points, campuses, rejectCounts);
    }

    /**
     * 卖家历史被驳回/违规的商品数（审核员判断"是不是惯犯"的参考）。
     * 一次 IN 查询 + 内存分组，避免列表里每个卖家各查一次。
     */
    private Map<Long, Integer> rejectCountsBySeller(List<Long> sellerIds) {
        Map<Long, Integer> counts = new HashMap<>();
        List<Long> ids = sellerIds == null ? List.of()
                : sellerIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return counts;
        }
        try {
            List<Item> rejected = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                    .select(Item::getSellerId)
                    .in(Item::getSellerId, ids)
                    .in(Item::getStatus, 6, 8));
            for (Item i : rejected) {
                counts.merge(i.getSellerId(), 1, Integer::sum);
            }
        } catch (Exception e) {
            log.debug("[review] 统计卖家驳回次数失败：{}", e.getMessage());
        }
        return counts;
    }

    /**
     * 待审条数。<b>口径必须与 {@link #page} 的 pending 完全一致</b>：
     * 实时推送里带的数字和列表条数对不上，审核员第一反应就是"这个页面坏了"。
     */
    public long pendingCount() {
        return reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                .inSql(ItemReview::getItemId, LIVE_ITEM_SQL));
    }

    /* ==================================================================
     *  四、启动修复：把"在售但没有审核记录"的商品补登记
     * ================================================================== */

    /**
     * 补登记：库里在售(2)/已锁定(3) 却没有审核记录的商品。
     *
     * <p>为什么需要：上线前就存在的商品、或某次入队真的失败了（异常被吞），
     * 这些商品永远不会出现在审核页里 —— 而"确保每条都被看过"的前提是"每条都在列表里"。
     * 由启动任务调用一次；补登记按<b>待审</b>入册（真的没人看过），reason 记为存量补录。
     *
     * @return 补登记条数
     */
    public int backfillMissing(int limit) {
        List<Item> liveItems = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .in(Item::getStatus, 2, 3)
                .orderByDesc(Item::getId)
                .last("LIMIT " + Math.max(limit, 1)));
        if (liveItems.isEmpty()) {
            return 0;
        }
        Set<Long> existing = reviewMapper.selectList(Wrappers.<ItemReview>lambdaQuery()
                        .in(ItemReview::getItemId, liveItems.stream().map(Item::getId).toList()))
                .stream().map(ItemReview::getItemId).collect(Collectors.toSet());
        int added = 0;
        for (Item item : liveItems) {
            if (existing.contains(item.getId())) {
                continue;
            }
            try {
                enqueue(item, ItemReview.TYPE_LEGACY, "存量商品补登记");
                added++;
            } catch (Exception e) {
                log.warn("[review][backfill][{}] 补登记失败：{}", item.getId(), e.getMessage());
            }
        }
        if (added > 0) {
            log.info("[review][backfill] 补登记 {} 条在售但没有审核记录的商品", added);
        }
        return added;
    }

    /* ==================================================================
     *  五、内部工具
     * ================================================================== */

    /** 卖家二次申请上架时，把待审记录重置（卖家改完内容再申请，不能算"已审核过"） */
    public void resetOnRelistApply(Long itemId) {
        try {
            reviewMapper.update(null, Wrappers.<ItemReview>lambdaUpdate()
                    .eq(ItemReview::getItemId, itemId)
                    .eq(ItemReview::getStatus, ItemReview.STATUS_DONE)
                    .set(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                    .set(ItemReview::getReviewAction, null)
                    .set(ItemReview::getReviewedAt, null)
                    .set(ItemReview::getReviewerId, null));
        } catch (Exception e) {
            log.warn("[review][relist-apply][{}] 重置审核记录失败：{}", itemId, e.getMessage());
        }
    }

    private ItemReview requireReview(Long itemId) {
        ItemReview review = selectByItemId(itemId);
        if (review == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND, "该商品没有上架审核记录，请刷新列表");
        }
        return review;
    }

    private ItemReview selectByItemId(Long itemId) {
        return reviewMapper.selectOne(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getItemId, itemId).last("LIMIT 1"));
    }

    /** 该商品进入过几次审核轮次（历史被违规下架过 = 轮次 ≥ 2，前端打「曾违规」标记） */
    private static int reviewRound(ItemReview r) {
        return r.getRevision() == null ? 1 : Math.max(r.getRevision(), 1);
    }

    private boolean contentChanged(Item item) {
        if (!StringUtils.hasText(item.getLastLiveTitle())) {
            // 没有快照（老数据未回填）时不能判定"改过"，否则第一次编辑会误报
            return false;
        }
        return !Objects.equals(item.getTitle(), item.getLastLiveTitle())
                || !Objects.equals(hash(item.getDescription()), item.getLastLiveDescHash());
    }

    private ItemReviewVO toVO(ItemReview r, Item item, Map<Long, SysUser> sellers, Map<Long, SysUser> reviewers,
                              Map<Long, ItemCategory> categories, Map<Long, TradePoint> points,
                              Map<Long, Campus> campuses, Map<Long, Integer> rejectCounts) {
        ItemReviewVO vo = new ItemReviewVO();
        vo.setId(r.getId());
        vo.setItemId(r.getItemId());
        vo.setTitleSnapshot(r.getTitle());
        vo.setTitle(item != null ? item.getTitle() : r.getTitle());
        vo.setStatus(r.getStatus());
        vo.setStatusText(r.getStatus() != null && r.getStatus() == ItemReview.STATUS_DONE ? "已审核" : "待审核");
        vo.setReviewAction(r.getReviewAction());
        vo.setReviewActionText(reviewActionText(r.getReviewAction()));
        vo.setReviewType(r.getReviewType());
        vo.setReviewTypeText(reviewTypeText(r.getReviewType()));
        vo.setRevision(r.getRevision());
        vo.setQueuedAt(r.getQueuedAt());
        vo.setFirstQueuedAt(r.getFirstQueuedAt());
        vo.setContentChangedAt(r.getContentChangedAt());
        vo.setReviewedAt(r.getReviewedAt());
        vo.setNote(r.getNote());
        vo.setReviewerId(r.getReviewerId());
        vo.setClaimedAt(r.getClaimedAt());
        vo.setReviewRound(reviewRound(r));
        vo.setRejectCount(rejectCounts.getOrDefault(r.getSellerId(), 0));
        // 「曾违规」：这一轮就是"违规后二次上架"入的队。
        // （不能用 revision>1 判断：改名也会让 revision 变大，那是「已改名」而不是「曾违规」。）
        vo.setViolationBefore(reviewType(r.getReviewType()) == ItemReview.TYPE_VIOLATION_RELIST);
        // 「已改名」：这一轮是改名入的队（revision>1 且类型为改名），或者
        // 商品当前标题与"审核员看过的那一版"对不上（改名后还没审核就又被改了）。
        // 注意不能用 r.title 判断：改名时 title 存的是"改之前的旧标题"（就是要给人看的对比信息），
        // 拿它跟当前标题比永远不相等。
        vo.setContentChanged(reviewType(r.getReviewType()) == ItemReview.TYPE_RENAMED
                || (item != null && r.getStatus() != null && r.getStatus() == ItemReview.STATUS_PENDING
                && StringUtils.hasText(item.getLastLiveTitle())
                && !Objects.equals(item.getTitle(), item.getLastLiveTitle())));

        if (item != null) {
            vo.setDescription(item.getDescription());
            vo.setCoverUrl(item.getCoverUrl());
            vo.setImages(readStringList(item.getImages()));
            vo.setPrice(item.getPrice());
            vo.setCategoryId(item.getCategoryId());
            vo.setCampusId(item.getCampusId());
            vo.setPreferPointId(item.getPreferPointId());
            vo.setItemStatus(item.getStatus());
            vo.setItemStatusText(ItemService.statusText(item.getStatus()));
            vo.setPublishedAt(item.getPublishedAt());
            vo.setExpireAt(item.getExpireAt());
            vo.setActionable(item.getStatus() != null && ACTIONABLE_STATUS.contains(item.getStatus()));
            ItemCategory category = pick(categories, item.getCategoryId());
            if (category != null) {
                vo.setCategoryName(category.getName());
            }
            TradePoint point = pick(points, item.getPreferPointId());
            if (point != null) {
                vo.setPreferPointName(point.getName());
            }
            Campus campus = pick(campuses, item.getCampusId());
            if (campus != null) {
                vo.setCampusName(campus.getName());
            }
        } else {
            // 商品被物理删除（清库脚本）：记录留着但什么也做不了
            vo.setActionable(false);
            vo.setItemStatusText("已删除");
        }

        SysUser seller = pick(sellers, r.getSellerId());
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerCreditScore(seller.getCreditScore());
        }
        SysUser reviewer = pick(reviewers, r.getReviewerId());
        if (reviewer != null) {
            vo.setReviewerNickname(reviewer.getNickname());
        }
        return vo;
    }

    private void afterItemWrite(Long itemId) {
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
    }

    private void broadcastHandled(Long itemId) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pending", pendingCount());
            data.put("itemId", itemId);
            data.put("onlineReviewers", pushService.onlineReviewerCount());
            pushService.broadcast("handled", data);
        } catch (Exception e) {
            log.debug("[review][push] handled 推送失败：{}", e.getMessage());
        }
    }

    public static String reviewTypeText(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case ItemReview.TYPE_NEW -> "新上架";
            case ItemReview.TYPE_RENAMED -> "改名/改描述";
            case ItemReview.TYPE_RELIST -> "重新上架";
            case ItemReview.TYPE_VIOLATION_RELIST -> "违规后二次上架";
            case ItemReview.TYPE_LEGACY -> "存量导入";
            default -> "未知";
        };
    }

    public static String reviewActionText(Integer action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case ItemReview.ACTION_REVIEWED -> "已审核";
            case ItemReview.ACTION_OFFLINE -> "已下架";
            default -> "未知";
        };
    }

    /** 商品描述可能为 null；MD5 只用于"内容是否变过"的判断，不做安全用途 */
    public static String hash(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** review_type 是 TINYINT，MyBatis 取出来可能是 Integer，统一收敛 */
    private static int reviewType(Integer type) {
        return type == null ? 0 : type;
    }

    private List<String> readStringList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return json.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String trim(String text, int max) {
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /** 关联 id 允许为 NULL，取关联对象必须 null-safe（Map.of() 的 get(null) 会抛 NPE） */
    private static <K, V> V pick(Map<K, V> map, K key) {
        return key == null ? null : map.get(key);
    }

    private static <T> T item(Map<Long, Item> items, Long itemId, java.util.function.Function<Item, T> getter) {
        Item item = itemId == null ? null : items.get(itemId);
        return item == null ? null : getter.apply(item);
    }

    private <T> Map<Long, T> mapBatch(List<Long> rawIds, java.util.function.Function<List<Long>, List<T>> loader,
                                      java.util.function.Function<T, Long> idGetter) {
        Map<Long, T> map = new HashMap<>();
        List<Long> ids = rawIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        for (T row : loader.apply(ids)) {
            map.putIfAbsent(idGetter.apply(row), row);
        }
        return map;
    }

    private static Map<String, Object> state(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
