package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.ItemClient;
import com.ershou.cloud.admin.dto.BatchReviewActionDTO;
import com.ershou.cloud.admin.vo.BatchAuditResultVO;
import com.ershou.cloud.admin.vo.ItemReviewStatsVO;
import com.ershou.cloud.admin.vo.ItemReviewVO;
import com.ershou.cloud.admin.config.ReviewProperties;
import com.ershou.cloud.admin.ws.ReviewOnlineRegistry;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 上架审核工作台（BFF → item-service /inner/item-reviews）。
 *
 * <p>item_review 表归 ershou_item（契约第四节），入队逻辑（发布/改名/违规二次申请）
 * 由 item-service 在商品写路径里完成；admin-service 只做审核员视角的读 + 处置编排 + 审计留痕。
 * 这样"每条上架都被看过"的完整性保证仍由 item 域的表来兜，不需要跨库 SQL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminReviewService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int REVIEW_PENDING = 0;
    private static final int REVIEW_DONE = 1;
    private static final String DEFAULT_OFFLINE_REASON = "上架审核：内容不符合规范，已下架";

    private final ItemClient itemClient;
    private final AdminUserDirectory userDirectory;
    private final AuditLogService auditLogService;
    private final ReviewProperties reviewProperties;
    private final ReviewOnlineRegistry onlineRegistry;

    /** 审核队列：scope = pending（默认）/done/all */
    public PageResult<ItemReviewVO> page(String scope, Long sellerId, String keyword, long pageNum, long pageSize) {
        Integer status = mapScope(scope);
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        PageResult<Map<String, Object>> res = FeignResults.data(
                itemClient.pageReviews(status, sellerId, keyword == null ? "" : keyword.trim(),
                        Math.max(pageNum, 1), size), "商品服务");
        if (res == null) {
            return PageResult.of(List.of(), 0, Math.max(pageNum, 1), size);
        }
        List<ItemReviewVO> list = res.getRecords() == null ? List.of()
                : res.getRecords().stream().map(this::toVO).toList();
        enrichSellers(list);
        return PageResult.of(list, res.getTotal(), res.getPageNum(), res.getPageSize());
    }

    /** item inner 只给 sellerId；批量补卖家昵称/信用分（失败容忍为空） */
    private void enrichSellers(List<ItemReviewVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> briefs = userDirectory.briefs(
                list.stream().map(ItemReviewVO::getSellerId)
                        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        if (briefs.isEmpty()) {
            return;
        }
        for (ItemReviewVO vo : list) {
            if (vo.getSellerNickname() == null) {
                vo.setSellerNickname(userDirectory.nickname(briefs, vo.getSellerId()));
            }
            if (vo.getSellerCreditScore() == null) {
                vo.setSellerCreditScore(userDirectory.creditScore(briefs, vo.getSellerId()));
            }
        }
    }

    public ItemReviewVO detail(Long itemId) {
        return toVO(FeignResults.data(itemClient.reviewDetail(itemId), "商品服务"));
    }

    /**
     * 统计：item-service /inner/item-reviews/stats 的 key 与契约对齐
     * {pending,todayApproved,todayRejected,reviewedToday,onSaleTotal}，映射到旧单体 VO。
     *
     * <p>主统计不可用时直接抛 80001（不静默 0，避免"0 条待审"误导运营）。
     */
    public ItemReviewStatsVO stats() {
        ItemReviewStatsVO vo = new ItemReviewStatsVO();
        Map<String, Object> stats = FeignResults.data(itemClient.reviewStats(), "商品服务");
        vo.setPending(longOf(stats, "pending", 0));
        // reviewedToday = 今日通过 + 今日驳回（item 侧已直接提供该键，兼容老 key）
        vo.setReviewedToday(longOf(stats, "reviewedToday", longOf(stats, "todayApproved", 0)));
        vo.setQueuedToday(longOf(stats, "queuedToday", 0));
        vo.setStalePending(longOf(stats, "stalePending", 0));
        long onSaleTotal = longOf(stats, "onSaleTotal", -1);
        if (onSaleTotal < 0) {
            try {
                Map<String, Object> itemStats = FeignResults.data(itemClient.itemStats(), "商品服务");
                onSaleTotal = longOf(itemStats, "onSale", 0);
            } catch (Exception ignored) {
                onSaleTotal = 0;
            }
        }
        vo.setOnSaleTotal(onSaleTotal);
        vo.setOnlineReviewers(onlineRegistry.count());
        vo.setServerTime(LocalDateTime.now());
        return vo;
    }

    /** 已审核（确认看过，无问题）→ 从待审列表归档 */
    public ItemReviewStatsVO markReviewed(Long itemId, String note, Long operatorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "approve");
        body.put("remark", note);
        body.put("operatorId", operatorId);
        FeignResults.data(itemClient.markReviewed(itemId, body), "商品服务");
        auditLogService.record(operatorId, "review", "review-approved", "item", itemId, null,
                state("note", note));
        log.info("[admin][review][{}][reviewed][operator={}]", itemId, operatorId);
        return stats();
    }

    /** 下架（内容不合适，撤下来；卖家改好可重新上架） */
    public ItemReviewStatsVO downlist(Long itemId, String reason, Long operatorId) {
        String finalReason = reason == null || reason.isBlank() ? DEFAULT_OFFLINE_REASON : reason.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", finalReason);
        body.put("operatorId", operatorId);
        FeignResults.data(itemClient.offlineReview(itemId, body), "商品服务");
        auditLogService.record(operatorId, "review", "review-offline", "item", itemId, null,
                state("reason", finalReason));
        log.info("[admin][review][{}][offline][operator={}]", itemId, operatorId);
        return stats();
    }

    /** 批量处置：action = review 已审核 / offline 下架 */
    public BatchAuditResultVO batch(BatchReviewActionDTO dto, String action, Long operatorId) {
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!"review".equals(act) && !"offline".equals(act)) {
            throw new BizException(ResultCode.PARAM_ERROR, "action 只能是 review 或 offline");
        }
        List<Long> ids = dto.getIds() == null ? List.of()
                : dto.getIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请先勾选要处置的商品");
        }
        if (ids.size() > reviewProperties.getMaxBatchSize()) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "单次最多处置 " + reviewProperties.getMaxBatchSize() + " 条");
        }
        String remark = dto.getReason() == null ? null : dto.getReason().trim();
        if ("offline".equals(act) && (remark == null || remark.isEmpty())) {
            throw new BizException(ResultCode.PARAM_ERROR, "批量下架必须填写原因");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("itemIds", ids);
        // item-service /inner/item-reviews/batch 的动作是 approve|reject；
        // 管理端语义 review=已审核无问题(approve)、offline=审核下架(reject)
        body.put("action", "review".equals(act) ? "approve" : "reject");
        body.put("remark", remark);
        body.put("operatorId", operatorId);
        Map<String, Object> result = FeignResults.data(itemClient.batchReview(body), "商品服务");
        BatchAuditResultVO vo = new BatchAuditResultVO();
        vo.setSuccess((int) longOf(result, "success", ids.size()));
        vo.setFailed((int) longOf(result, "failed", 0));
        List<BatchAuditResultVO.Failure> failures = new ArrayList<>();
        Object rawFailures = result == null ? null : result.get("failures");
        if (rawFailures instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> f) {
                    Object itemId = f.get("itemId");
                    Object failReason = f.get("reason");
                    failures.add(new BatchAuditResultVO.Failure(
                            itemId instanceof Number n ? n.longValue() : null,
                            failReason == null ? null : String.valueOf(failReason)));
                }
            }
        }
        vo.setFailures(failures);
        auditLogService.record(operatorId, "review", "batch-" + act, "item", null,
                state("ids", ids), state("success", vo.getSuccess(), "failed", vo.getFailed(), "remark", remark));
        log.info("[admin][review][batch-{}] success={} failed={} operator={}", act, vo.getSuccess(), vo.getFailed(), operatorId);
        return vo;
    }

    /** 补登记：把在售却没有审核记录的商品补进队列（运营手动兜底） */
    public int backfill(int limit, Long operatorId) {
        Integer added = FeignResults.data(itemClient.backfillReviews(limit), "商品服务");
        int count = added == null ? 0 : added;
        auditLogService.record(operatorId, "review", "backfill", "item_review", null, null,
                state("limit", limit, "added", count));
        log.info("[admin][review][backfill] added={} operator={}", count, operatorId);
        return count;
    }

    /* ------------------------------------------------------------------ */

    private static Integer mapScope(String scope) {
        if (scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope)) {
            return null;
        }
        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "pending" -> REVIEW_PENDING;
            case "done" -> REVIEW_DONE;
            default -> null;
        };
    }

    private static long longOf(Map<String, Object> map, String key, long defaultValue) {
        Long v = MapHelper.lng(map, key);
        return v == null ? defaultValue : v;
    }

    private ItemReviewVO toVO(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        ItemReviewVO vo = new ItemReviewVO();
        vo.setId(MapHelper.lng(m, "id"));
        vo.setItemId(MapHelper.lng(m, "itemId"));
        vo.setTitle(MapHelper.str(m, "title"));
        vo.setTitleSnapshot(MapHelper.str(m, "titleSnapshot"));
        vo.setDescription(MapHelper.str(m, "description"));
        vo.setCoverUrl(MapHelper.str(m, "coverUrl"));
        vo.setImages(MapHelper.strList(m, "images"));
        vo.setSellerId(MapHelper.lng(m, "sellerId"));
        vo.setSellerNickname(MapHelper.str(m, "sellerNickname"));
        vo.setSellerPhone(MapHelper.str(m, "sellerPhone"));
        vo.setSellerCreditScore(MapHelper.intg(m, "sellerCreditScore"));
        vo.setCategoryId(MapHelper.lng(m, "categoryId"));
        vo.setCategoryName(MapHelper.str(m, "categoryName"));
        vo.setCampusId(MapHelper.lng(m, "campusId"));
        vo.setCampusName(MapHelper.str(m, "campusName"));
        vo.setPreferPointId(MapHelper.lng(m, "preferPointId"));
        vo.setPreferPointName(MapHelper.str(m, "preferPointName"));
        vo.setPrice(MapHelper.dec(m, "price"));
        vo.setItemStatus(MapHelper.intg(m, "itemStatus"));
        vo.setItemStatusText(AdminDicts.itemStatusText(vo.getItemStatus()));
        vo.setActionable(MapHelper.bool(m, "actionable"));
        vo.setReviewType(MapHelper.intg(m, "reviewType"));
        vo.setReviewTypeText(reviewTypeText(vo.getReviewType()));
        vo.setRevision(MapHelper.intg(m, "revision"));
        vo.setStatus(MapHelper.intg(m, "status"));
        vo.setStatusText(reviewStatusText(vo.getStatus()));
        vo.setReviewAction(MapHelper.intg(m, "reviewAction"));
        vo.setReviewActionText(reviewActionText(vo.getReviewAction()));
        vo.setQueuedAt(MapHelper.dt(m, "queuedAt"));
        vo.setFirstQueuedAt(MapHelper.dt(m, "firstQueuedAt"));
        vo.setContentChangedAt(MapHelper.dt(m, "contentChangedAt"));
        vo.setReviewedAt(MapHelper.dt(m, "reviewedAt"));
        vo.setNote(MapHelper.str(m, "note"));
        vo.setReviewerId(MapHelper.lng(m, "reviewerId"));
        vo.setReviewerNickname(MapHelper.str(m, "reviewerNickname"));
        vo.setClaimedAt(MapHelper.dt(m, "claimedAt"));
        vo.setContentChanged(MapHelper.bool(m, "contentChanged"));
        vo.setViolationBefore(MapHelper.bool(m, "violationBefore"));
        vo.setReviewRound(MapHelper.intg(m, "reviewRound"));
        vo.setRejectCount(MapHelper.intg(m, "rejectCount"));
        vo.setPublishedAt(MapHelper.dt(m, "publishedAt"));
        vo.setExpireAt(MapHelper.dt(m, "expireAt"));
        return vo;
    }

    private static String reviewTypeText(Integer type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case 1 -> "新上架";
            case 2 -> "改名/改描述";
            case 3 -> "重新上架";
            case 4 -> "违规后二次申请";
            case 5 -> "存量导入";
            default -> "未知";
        };
    }

    private static String reviewStatusText(Integer status) {
        if (status == null) {
            return null;
        }
        return status == REVIEW_PENDING ? "待审核" : "已审核";
    }

    private static String reviewActionText(Integer action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case 1 -> "已审核";
            case 2 -> "已下架";
            default -> "未知";
        };
    }

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 供 dashboard 使用：审核待办数（失败容忍 0） */
    public long pendingCountTolerant() {
        try {
            Map<String, Object> stats = FeignResults.data(itemClient.reviewStats(), "商品服务");
            return longOf(stats, "pending", 0);
        } catch (Exception e) {
            return 0L;
        }
    }
}
