package com.ershou.cloud.item.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.feign.InnerUserVO;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.search.ItemIndexService;
import com.ershou.cloud.item.service.ItemService;
import com.ershou.cloud.item.support.RemoteService;
import com.ershou.cloud.item.support.TxHooks;
import com.ershou.cloud.item.support.cache.CacheEvictor;
import com.ershou.cloud.item.support.cache.DictionaryCache;
import com.ershou.cloud.item.vo.PointVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 上架审核队列（{@code item_review}，T102 拆分后归属 ershou_item 库；admin-service 经 {@code /inner/item-reviews/**} 读写）。
 *
 * <p>行为对齐旧单体 {@code module/admin/service/ItemReviewService}，做了两处拆分适配：
 * <ul>
 *   <li><b>去掉 WebSocket 实时推送</b>：旧单体审核页靠 WS 推送；拆分后 WS 归 chat-service，
 *       由 admin-service 轮询 inner 接口（本类保留 pendingCount/stats 供其取数）——记遗留项；</li>
 *   <li><b>去掉运营审计日志</b>：{@code sys_audit_log} 归 ershou_admin，写日志由 admin-service 负责；</li>
 *   <li><b>卖家/点位信息</b>：经 {@link RemoteService}（user/map-service）补齐，不可用则留空降级。</li>
 * </ul>
 *
 * <p>队列规则（与单体一致）：一件商品只保留一条当前记录（uk_item），重复入队累加 revision；
 * 入队时刷新 {@code last_live_title/last_live_desc_hash} 快照，供"在售期间改名/改描述"识别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemReviewService {

    private final ItemMapper itemMapper;
    private final ItemReviewMapper reviewMapper;
    private final ItemIndexService itemIndexService;
    private final CacheEvictor evictor;
    private final DictionaryCache dict;
    private final RemoteService remote;

    // ------------------------------------------------------------------ 写路径入口（ItemService 调用）

    /**
     * 商品进入在售的登记入口：刷新"审核员已看过的那一版"快照 + 入队。
     * 调用点：发布、审核通过、重新上架、存量修复。
     */
    public void onItemLiveOptimistic(Long itemId, int reviewType) {
        try {
            Item item = itemMapper.selectById(itemId);
            if (item == null) {
                return;
            }
            enqueue(item, reviewType, null, null);
        } catch (Exception e) {
            // 登记失败不影响发布/审核本身
            log.warn("[review][enqueue][{}] 入队失败（不影响商品状态）：{}", itemId, e.getMessage());
        }
    }

    /**
     * 在售期间内容变更登记：改名/改描述后重新回到审核页。
     * {@code rowContentChanged=null} 时按快照比对。
     */
    public void onContentMaybeChanged(Long itemId, Boolean rowContentChanged) {
        try {
            Item item = itemMapper.selectById(itemId);
            if (item == null || item.getStatus() == null || item.getStatus() != 2) {
                return; // 不在售的商品不需要"重新确认在售内容"
            }
            boolean changed = rowContentChanged != null ? rowContentChanged : contentChanged(item);
            if (!changed) {
                return;
            }
            // 传"改之前"的标题作为快照：审核员要看的是"从什么改成了什么"
            enqueue(item, ItemReview.TYPE_RENAMED, null, item.getLastLiveTitle());
            log.info("[review][renamed][{}] 在售期间内容变更，重新进入上架审核页", itemId);
        } catch (Exception e) {
            log.warn("[review][renamed][{}] 内容变更登记失败（不影响商品本身）：{}", itemId, e.getMessage());
        }
    }

    /** 违规商品二次申请上架：转入审核中后重新入队（供运营处置） */
    public void notifyViolationRelistApplied(Long itemId, String title) {
        try {
            Item item = itemMapper.selectById(itemId);
            if (item != null) {
                enqueue(item, ItemReview.TYPE_VIOLATION_RELIST, null, null);
            }
            log.info("[review][violation-apply][{}] {} 二次申请上架，已重新进入审核队列", itemId, title);
        } catch (Exception e) {
            log.warn("[review][violation-apply][{}] 入队失败（不影响申请）：{}", itemId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 审核动作（admin inner 调用）

    /**
     * 审核通过：队列记录置「已审核」；商品若处于审核中(1) 则同时放行到在售(2)。
     * （新上架商品发布时已是 2，这里只确认"看过"；违规二次申请的商品是 1，需要真正放行。）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(Long itemId, String remark, Long operatorId) {
        Item item = requireItem(itemId);
        if (item.getStatus() != null && item.getStatus() == 1) {
            int rows = itemMapper.approveAudit(itemId, StringUtils.hasText(remark) ? remark : "审核通过");
            if (rows == 0) {
                throw new BizException(ResultCode.REVIEW_ALREADY_HANDLED);
            }
            afterItemWrite(itemId);
        }
        int updated = reviewMapper.update(null, Wrappers.<ItemReview>lambdaUpdate()
                .eq(ItemReview::getItemId, itemId)
                .eq(ItemReview::getStatus, ItemReview.STATUS_PENDING)
                .set(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .set(ItemReview::getReviewAction, ItemReview.ACTION_REVIEWED)
                .set(ItemReview::getReviewerId, operatorId)
                .set(ItemReview::getReviewedAt, LocalDateTime.now())
                .set(ItemReview::getNote, StringUtils.hasText(remark) ? trim(remark, 200) : null));
        if (updated == 0) {
            // 队列里没有待审记录（可能已被处置）：幂等返回，不报错
            log.info("[review][approve][{}] 无待审记录（已处置过），幂等返回 operator={}", itemId, operatorId);
        }
        log.info("[review][approve][{}][operator={}] remark={}", itemId, operatorId, remark);
        return statsMap();
    }

    /**
     * 审核驳回：商品若在审核中(1) → 审核未通过(6)；在售(2)/其他可撤状态 → 已下架(5)。
     * 队列记录置「已下架」，note 记原因。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reject(Long itemId, String remark, Long operatorId) {
        Item item = requireItem(itemId);
        String reason = StringUtils.hasText(remark) ? trim(remark, 200) : "审核未通过";
        Integer status = item.getStatus();
        int rows;
        if (status != null && status == 1) {
            rows = itemMapper.rejectAudit(itemId, reason, null);
        } else {
            rows = itemMapper.downlist(itemId);
            if (rows > 0) {
                int closed = remote.closeActiveOrders(itemId, "商品审核未通过，本次约面交自动作废");
                log.info("[review][reject][{}] 下架并作废 {} 张挂单", itemId, closed);
            }
        }
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_ALREADY_HANDLED);
        }
        afterItemWrite(itemId);
        reviewMapper.update(null, Wrappers.<ItemReview>lambdaUpdate()
                .eq(ItemReview::getItemId, itemId)
                .set(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .set(ItemReview::getReviewAction, ItemReview.ACTION_OFFLINE)
                .set(ItemReview::getReviewerId, operatorId)
                .set(ItemReview::getReviewedAt, LocalDateTime.now())
                .set(ItemReview::getNote, reason));
        log.info("[review][reject][{}][operator={}] 原 status={} 原因：{}", itemId, operatorId, status, reason);
        return statsMap();
    }

    /**
     * 审核页「下架」：内容不合适但够不上违规 → 已下架(5)，卖家可改完重新上架；
     * 同时清锁库信息并作废挂单（与单体 downlist 一致）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> downlist(Long itemId, String reason, Long operatorId) {
        Item before = requireItem(itemId);
        String text = StringUtils.hasText(reason) ? trim(reason, 200) : "运营下架";
        int rows = itemMapper.downlist(itemId);
        if (rows == 0) {
            throw new BizException(ResultCode.REVIEW_NOT_ACTIONABLE, "该商品已不在可处置状态，请刷新列表");
        }
        int closed = remote.closeActiveOrders(itemId, "商品已被平台下架，本次约面交自动作废");
        afterItemWrite(itemId);
        reviewMapper.update(null, Wrappers.<ItemReview>lambdaUpdate()
                .eq(ItemReview::getItemId, itemId)
                .set(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .set(ItemReview::getReviewAction, ItemReview.ACTION_OFFLINE)
                .set(ItemReview::getReviewerId, operatorId)
                .set(ItemReview::getReviewedAt, LocalDateTime.now())
                .set(ItemReview::getNote, text));
        log.info("[review][downlist][{}][operator={}] 从 status={} 下架，作废 {} 张挂单；原因：{}",
                itemId, operatorId, before.getStatus(), closed, text);
        return statsMap();
    }

    /** 批量处置：action=approve|reject，逐条容忍失败（返回成功/失败明细） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batch(List<Long> itemIds, String action, String remark, Long operatorId) {
        List<Long> ids = itemIds == null ? List.of() : itemIds.stream().filter(Objects::nonNull).distinct().toList();
        int success = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Long id : ids) {
            try {
                if ("reject".equalsIgnoreCase(action)) {
                    reject(id, remark, operatorId);
                } else {
                    approve(id, remark, operatorId);
                }
                success++;
            } catch (Exception e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("itemId", id);
                f.put("reason", e.getMessage());
                failures.add(f);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failures.size());
        result.put("failures", failures);
        log.info("[review][batch][operator={}] action={} total={} success={} failed={}",
                operatorId, action, ids.size(), success, failures.size());
        return result;
    }

    /** 存量补登记：给「还没有审核记录的在售/审核中商品」补一条待审记录，返回补录条数 */
    @Transactional(rollbackFor = Exception.class)
    public int backfillMissing(int limit) {
        int max = Math.max(limit, 1);
        List<Item> items = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                .in(Item::getStatus, 1, 2, 3)
                .orderByDesc(Item::getId)
                .last("LIMIT " + max));
        int count = 0;
        for (Item item : items) {
            ItemReview existing = selectByItemId(item.getId());
            if (existing != null) {
                continue;
            }
            enqueue(item, ItemReview.TYPE_LEGACY, "存量商品补登记", null);
            count++;
        }
        log.info("[review][backfill] 补登记 {} 条（扫描 {} 件商品）", count, items.size());
        return count;
    }

    // ------------------------------------------------------------------ 读路径（admin inner 调用）

    /** 审核队列分页：status=0 待审 / 1 已审 / 空=全部；keyword 支持商品标题模糊（本表 title 快照） */
    public PageResult<ItemReviewVO> page(String status, String keyword, long pageNum, long pageSize) {
        long num = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), 100);
        LambdaQueryWrapper<ItemReview> w = Wrappers.<ItemReview>lambdaQuery();
        if ("pending".equalsIgnoreCase(status)) {
            w.eq(ItemReview::getStatus, ItemReview.STATUS_PENDING);
        } else if ("done".equalsIgnoreCase(status)) {
            w.eq(ItemReview::getStatus, ItemReview.STATUS_DONE);
        } else if (StringUtils.hasText(status)) {
            try {
                w.eq(ItemReview::getStatus, Integer.parseInt(status.trim()));
            } catch (NumberFormatException ignore) {
                // 非法状态按全部处理
            }
        }
        if (StringUtils.hasText(keyword)) {
            w.like(ItemReview::getTitle, keyword.trim());
        }
        w.orderByAsc(ItemReview::getStatus).orderByAsc(ItemReview::getQueuedAt);
        Page<ItemReview> page = reviewMapper.selectPage(new Page<>(num, size), w);
        return PageResult.of(toVOList(page.getRecords()), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 单条审核详情（itemId）；不存在返回 null */
    public ItemReviewVO detail(Long itemId) {
        ItemReview row = selectByItemId(itemId);
        if (row == null) {
            return null;
        }
        return toVOList(List.of(row)).stream().findFirst().orElse(null);
    }

    /** 待审数量（admin 待办角标） */
    public long pendingCount() {
        Long count = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_PENDING));
        return count == null ? 0 : count;
    }

    /** 审核统计：pending / todayApproved / todayRejected（契约 §六） */
    public Map<String, Object> statsMap() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long pending = pendingCount();
        Long approved = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .eq(ItemReview::getReviewAction, ItemReview.ACTION_REVIEWED)
                .ge(ItemReview::getReviewedAt, todayStart));
        Long rejected = reviewMapper.selectCount(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getStatus, ItemReview.STATUS_DONE)
                .eq(ItemReview::getReviewAction, ItemReview.ACTION_OFFLINE)
                .ge(ItemReview::getReviewedAt, todayStart));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("todayApproved", approved == null ? 0 : approved);
        result.put("todayRejected", rejected == null ? 0 : rejected);
        // 兼容旧单体 ItemReviewStatsVO 的字段名（admin-service 若按旧 VO 解析也能用）
        result.put("reviewedToday", (approved == null ? 0 : approved) + (rejected == null ? 0 : rejected));
        result.put("onSaleTotal", onSaleCount());
        return result;
    }

    /** 在售商品总数（/inner/items/stats 用） */
    public long onSaleCount() {
        Long count = itemMapper.selectCount(Wrappers.<Item>lambdaQuery().eq(Item::getStatus, 2));
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ 内部：入队/快照/组装

    private ItemReview selectByItemId(Long itemId) {
        return reviewMapper.selectOne(Wrappers.<ItemReview>lambdaQuery()
                .eq(ItemReview::getItemId, itemId).last("LIMIT 1"));
    }

    /** 入队/重新入队：一个商品只保留一条记录，重新入队累加 revision */
    private void enqueue(Item item, int reviewType, String note, String titleSnapshot) {
        LocalDateTime now = LocalDateTime.now();
        // 先刷新"审核员已看过的那一版"快照（必须在取旧标题之后由调用方传入）
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
        row.setContentChangedAt(reviewType == ItemReview.TYPE_RENAMED ? now
                : (existing == null ? null : existing.getContentChangedAt()));
        row.setFirstQueuedAt(existing != null && existing.getFirstQueuedAt() != null
                ? existing.getFirstQueuedAt()
                : (existing != null ? existing.getQueuedAt() : now));
        row.setRevision(existing == null || existing.getRevision() == null ? 1 : existing.getRevision() + 1);

        if (existing == null) {
            reviewMapper.insert(row);
        } else {
            row.setId(existing.getId());
            reviewMapper.updateById(row);
        }
        log.info("[review][enqueue][{}] type={} revision={} pending={}",
                item.getId(), reviewType, row.getRevision(), pendingCount());
    }

    private boolean contentChanged(Item item) {
        if (item.getLastLiveTitle() == null && item.getLastLiveDescHash() == null) {
            return false;
        }
        return !Objects.equals(item.getTitle(), item.getLastLiveTitle())
                || !Objects.equals(hash(item.getDescription()), item.getLastLiveDescHash());
    }

    private Item requireItem(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return item;
    }

    /** 写后统一收尾：ES 同步 + 详情缓存失效 */
    private void afterItemWrite(Long itemId) {
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
        TxHooks.afterCommit(() -> evictor.itemChanged(itemId));
    }

    private List<ItemReviewVO> toVOList(List<ItemReview> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = rows.stream().map(ItemReview::getItemId).filter(Objects::nonNull).distinct().toList();
        List<Item> items = itemIds.isEmpty() ? List.of() : itemMapper.selectBatchIds(itemIds);
        Map<Long, Item> itemMap = new LinkedHashMap<>();
        for (Item item : items) {
            itemMap.put(item.getId(), item);
        }
        Map<Long, InnerUserVO> sellers = remote.users(
                rows.stream().map(ItemReview::getSellerId).filter(Objects::nonNull).toList());
        Map<Long, String> categoryNames = dict.categoryNames(
                items.stream().map(Item::getCategoryId).filter(Objects::nonNull).toList());
        Map<Long, PointVO> points = remote.pointsForItems(items);

        List<ItemReviewVO> vos = new ArrayList<>(rows.size());
        for (ItemReview r : rows) {
            ItemReviewVO vo = new ItemReviewVO();
            vo.setId(r.getId());
            vo.setItemId(r.getItemId());
            vo.setTitle(r.getTitle());
            vo.setTitleSnapshot(r.getTitle());
            vo.setSellerId(r.getSellerId());
            vo.setReviewType(r.getReviewType());
            vo.setReviewTypeText(reviewTypeText(r.getReviewType()));
            vo.setRevision(r.getRevision());
            vo.setStatus(r.getStatus());
            vo.setStatusText(r.getStatus() != null && r.getStatus() == ItemReview.STATUS_DONE ? "已审核" : "待审核");
            vo.setReviewAction(r.getReviewAction());
            vo.setReviewActionText(reviewActionText(r.getReviewAction()));
            vo.setQueuedAt(r.getQueuedAt());
            vo.setFirstQueuedAt(r.getFirstQueuedAt());
            vo.setContentChangedAt(r.getContentChangedAt());
            vo.setReviewedAt(r.getReviewedAt());
            vo.setNote(r.getNote());
            vo.setReviewerId(r.getReviewerId());
            vo.setClaimedAt(r.getClaimedAt());

            Item item = itemMap.get(r.getItemId());
            if (item != null) {
                vo.setDescription(item.getDescription());
                vo.setCoverUrl(item.getCoverUrl());
                vo.setCategoryId(item.getCategoryId());
                vo.setCampusId(item.getCampusId());
                vo.setPreferPointId(item.getPreferPointId());
                vo.setPrice(item.getPrice());
                vo.setItemStatus(item.getStatus());
                vo.setItemStatusText(ItemService.statusText(item.getStatus()));
                vo.setPublishedAt(item.getPublishedAt());
                vo.setExpireAt(item.getExpireAt());
                vo.setActionable(item.getStatus() != null && (item.getStatus() == 1 || item.getStatus() == 2
                        || item.getStatus() == 3 || item.getStatus() == 6));
                vo.setContentChanged(contentChanged(item));
                vo.setViolationBefore(item.getAuditResult() != null && item.getAuditResult() == 2);
                if (item.getCategoryId() != null) {
                    vo.setCategoryName(categoryNames.get(item.getCategoryId()));
                }
                if (item.getPreferPointId() != null) {
                    PointVO p = points.get(item.getPreferPointId());
                    if (p != null) {
                        vo.setPreferPointName(p.getName());
                    }
                }
            }
            InnerUserVO seller = r.getSellerId() == null ? null : sellers.get(r.getSellerId());
            if (seller != null) {
                vo.setSellerNickname(seller.getNickname());
                vo.setSellerCreditScore(seller.getCreditScore());
                vo.setCampusName(seller.getCampusName());
            }
            vos.add(vo);
        }
        return vos;
    }

    private static String trim(String text, int max) {
        String t = text == null ? null : text.trim();
        if (t == null) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    public static String reviewTypeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case ItemReview.TYPE_NEW -> "新上架";
            case ItemReview.TYPE_RENAMED -> "改名/改描述";
            case ItemReview.TYPE_RELIST -> "重新上架";
            case ItemReview.TYPE_VIOLATION_RELIST -> "违规后二次上架";
            case ItemReview.TYPE_LEGACY -> "存量导入";
            default -> "其他";
        };
    }

    public static String reviewActionText(Integer action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case ItemReview.ACTION_REVIEWED -> "已审核";
            case ItemReview.ACTION_OFFLINE -> "已下架";
            default -> "其他";
        };
    }

    /** 描述 MD5（与旧单体同算法：UTF-8 MD5 小写十六进制），用于识别在售期间改名/改描述 */
    public static String hash(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
