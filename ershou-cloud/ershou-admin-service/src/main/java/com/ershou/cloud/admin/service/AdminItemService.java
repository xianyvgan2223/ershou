package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.ItemClient;
import com.ershou.cloud.admin.client.UserClient;
import com.ershou.cloud.admin.dto.BatchItemAuditDTO;
import com.ershou.cloud.admin.dto.ItemRejectDTO;
import com.ershou.cloud.admin.vo.AdminItemVO;
import com.ershou.cloud.admin.vo.BatchAuditResultVO;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品审核/处置（BFF → item-service）。
 *
 * <p>旧单体 AdminItemService 的职责拆分：状态流转（approve=2 / reject=6 / forceOffline=8）
 * 与 reject_code/audit_reason 落库归 item-service（item 域）；本服务保留编排 + 运营审计日志，
 * 以及前端需要的 AdminItemVO 结构（卖家昵称等由 user-service 补充，失败容忍为空）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminItemService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int STATUS_AUDITING = 1;
    private static final int STATUS_ON_SALE = 2;
    private static final int STATUS_REJECTED = 6;
    private static final int STATUS_VIOLATION = 8;
    private static final String APPROVE_REASON = "人工复核通过";

    private final ItemClient itemClient;
    private final UserClient userClient;
    private final AdminUserDirectory userDirectory;
    private final AuditLogService auditLogService;

    /** 商品管理列表（status 为空 = 全部状态）；审核工作台传 status=1 */
    public PageResult<AdminItemVO> page(Integer status, String keyword, Long sellerId,
                                        long pageNum, long pageSize) {
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        PageResult<Map<String, Object>> res = FeignResults.data(
                itemClient.pageItems(status, keyword == null ? "" : keyword.trim(), sellerId,
                        Math.max(pageNum, 1), size), "商品服务");
        if (res == null) {
            return PageResult.of(List.of(), 0, Math.max(pageNum, 1), size);
        }
        List<AdminItemVO> list = res.getRecords() == null ? List.of()
                : res.getRecords().stream().map(this::toVO).toList();
        enrichSellers(list);
        return PageResult.of(list, res.getTotal(), res.getPageNum(), res.getPageSize());
    }

    /** item inner 只给 sellerId；批量补昵称/信用分（user-service 不可用时容忍为空） */
    private void enrichSellers(List<AdminItemVO> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Map<Long, Map<String, Object>> briefs = userDirectory.briefs(
                list.stream().map(AdminItemVO::getSellerId).filter(Objects::nonNull).collect(Collectors.toSet()));
        if (briefs.isEmpty()) {
            return;
        }
        for (AdminItemVO vo : list) {
            if (vo.getSellerNickname() == null) {
                vo.setSellerNickname(userDirectory.nickname(briefs, vo.getSellerId()));
            }
            if (vo.getSellerCreditScore() == null) {
                vo.setSellerCreditScore(userDirectory.creditScore(briefs, vo.getSellerId()));
            }
        }
    }

    /** 审核通过：仅审核中(1)→在售(2)；item-service 条件更新兜并发 */
    public void approve(Long itemId, Long operatorId) {
        Map<String, Object> before = getItemTolerant(itemId);
        FeignResults.data(itemClient.updateItemStatus(itemId, STATUS_ON_SALE, APPROVE_REASON, null), "商品服务");
        auditLogService.record(operatorId, "item", "approve", "item", itemId,
                state("status", MapHelper.intg(before, "status")), state("status", STATUS_ON_SALE, "reason", APPROVE_REASON));
        log.info("[admin][item][{}][approve][operator={}]", itemId, operatorId);
    }

    /** 审核驳回：仅审核中(1)→审核未通过(6)；驳回原因必填（模板或补充说明） */
    public void reject(Long itemId, ItemRejectDTO dto, Long operatorId) {
        String reason = AuditReasons.compose(AuditReasons.SCOPE_ITEM, dto.getReasonCode(), dto.getReason());
        Map<String, Object> before = getItemTolerant(itemId);
        FeignResults.data(itemClient.updateItemStatus(itemId, STATUS_REJECTED, reason, dto.getReasonCode()), "商品服务");
        auditLogService.record(operatorId, "item", "reject", "item", itemId,
                state("status", MapHelper.intg(before, "status")),
                state("status", STATUS_REJECTED, "reasonCode", dto.getReasonCode(), "reason", reason));
        log.info("[admin][item][{}][reject][{}][operator={}]", itemId, dto.getReasonCode(), operatorId);
    }

    /** 批量通过/驳回：逐条条件更新，单条失败不影响其它（与单体一致） */
    public BatchAuditResultVO batch(BatchItemAuditDTO dto, Long operatorId) {
        String action = dto.getAction() == null ? "" : dto.getAction().trim().toLowerCase(Locale.ROOT);
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw new BizException(ResultCode.PARAM_ERROR, "action 只能是 approve 或 reject");
        }
        String reason = "reject".equals(action)
                ? AuditReasons.compose(AuditReasons.SCOPE_ITEM, dto.getReasonCode(), dto.getReason())
                : null;
        List<Long> ids = dto.getIds().stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, Map<String, Object>> items = batchItemsTolerant(ids);
        int success = 0;
        List<BatchAuditResultVO.Failure> failures = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> item = items.get(id);
            if (item == null) {
                failures.add(new BatchAuditResultVO.Failure(id, "商品不存在"));
                continue;
            }
            Integer status = MapHelper.intg(item, "status");
            if (status == null || status != STATUS_AUDITING) {
                failures.add(new BatchAuditResultVO.Failure(id, "状态不是“审核中”（" + AdminDicts.itemStatusText(status) + "）"));
                continue;
            }
            try {
                int target = "approve".equals(action) ? STATUS_ON_SALE : STATUS_REJECTED;
                FeignResults.data(itemClient.updateItemStatus(id, target,
                        "approve".equals(action) ? APPROVE_REASON : reason,
                        "reject".equals(action) ? dto.getReasonCode() : null), "商品服务");
                success++;
            } catch (BizException e) {
                failures.add(new BatchAuditResultVO.Failure(id, e.getMessage()));
            }
        }
        auditLogService.record(operatorId, "item", "batch-" + action, "item", null,
                state("ids", ids), state("success", success, "failed", failures.size(),
                        "reasonCode", dto.getReasonCode(), "reason", reason));
        BatchAuditResultVO vo = new BatchAuditResultVO();
        vo.setSuccess(success);
        vo.setFailed(failures.size());
        vo.setFailures(failures);
        log.info("[admin][item][batch-{}] success={} failed={} operator={}", action, success, failures.size(), operatorId);
        return vo;
    }

    /** 违规强制下架（不代表审核驳回，用于在售商品的事后处置） */
    public void forceOffline(Long itemId, String reason, Long operatorId) {
        Map<String, Object> before = getItemTolerant(itemId);
        FeignResults.data(itemClient.updateItemStatus(itemId, STATUS_VIOLATION, reason, null), "商品服务");
        auditLogService.record(operatorId, "item", "force-offline", "item", itemId,
                state("status", MapHelper.intg(before, "status")), state("status", STATUS_VIOLATION, "reason", reason));
        log.info("[admin][item][{}][force-offline][operator={}]", itemId, operatorId);
    }

    /* ------------------------------------------------------------------ */

    private Map<String, Object> getItemTolerant(Long itemId) {
        try {
            Map<String, Object> item = FeignResults.data(itemClient.getItem(itemId), "商品服务");
            if (item == null) {
                throw new BizException(ResultCode.ITEM_NOT_FOUND);
            }
            return item;
        } catch (BizException e) {
            if (e.getCode() == ResultCode.SERVICE_UNAVAILABLE.getCode()) {
                // 降级时无法拿 before，但状态变更本身仍会失败；交给后续调用报错
                log.warn("[admin][item][{}] 读取商品失败（降级）：{}", itemId, e.getMessage());
                return Map.of();
            }
            throw e;
        }
    }

    private Map<Long, Map<String, Object>> batchItemsTolerant(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> items = FeignResults.data(itemClient.batchItems(
                    ids.stream().map(String::valueOf).collect(Collectors.joining(","))), "商品服务");
            Map<Long, Map<String, Object>> map = new HashMap<>();
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Long id = MapHelper.lng(item, "id");
                    if (id != null) {
                        map.putIfAbsent(id, item);
                    }
                }
            }
            return map;
        } catch (BizException e) {
            log.warn("[admin][item] 批量读取商品失败（降级，逐条重新判定）：{}", e.getMessage());
            return Map.of();
        }
    }

    private AdminItemVO toVO(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        AdminItemVO vo = new AdminItemVO();
        vo.setId(MapHelper.lng(m, "id"));
        vo.setTitle(MapHelper.str(m, "title"));
        vo.setDescription(MapHelper.str(m, "description"));
        vo.setPrice(MapHelper.dec(m, "price"));
        vo.setOriginalPrice(MapHelper.dec(m, "originalPrice"));
        vo.setConditionLevel(MapHelper.intg(m, "conditionLevel"));
        vo.setConditionText(AdminDicts.conditionText(vo.getConditionLevel()));
        vo.setCoverUrl(MapHelper.str(m, "coverUrl"));
        vo.setImages(MapHelper.strList(m, "images"));
        vo.setCategoryId(MapHelper.lng(m, "categoryId"));
        vo.setCategoryName(MapHelper.str(m, "categoryName"));
        vo.setCampusId(MapHelper.lng(m, "campusId"));
        vo.setCampusName(MapHelper.str(m, "campusName"));
        vo.setPreferPointId(MapHelper.lng(m, "preferPointId"));
        vo.setPreferPointName(MapHelper.str(m, "preferPointName"));
        vo.setSellerId(MapHelper.lng(m, "sellerId"));
        vo.setSellerNickname(MapHelper.str(m, "sellerNickname"));
        vo.setSellerPhone(MapHelper.str(m, "sellerPhone"));
        vo.setSellerCreditScore(MapHelper.intg(m, "sellerCreditScore"));
        vo.setStatus(MapHelper.intg(m, "status"));
        vo.setStatusText(AdminDicts.itemStatusText(vo.getStatus()));
        vo.setAuditResult(MapHelper.intg(m, "auditResult"));
        vo.setAuditReason(MapHelper.str(m, "auditReason"));
        vo.setRejectCode(MapHelper.str(m, "rejectCode"));
        vo.setViewCount(MapHelper.intg(m, "viewCount"));
        vo.setFavCount(MapHelper.intg(m, "favCount"));
        vo.setStock(MapHelper.intg(m, "stock"));
        vo.setPublishedAt(MapHelper.dt(m, "publishedAt"));
        vo.setExpireAt(MapHelper.dt(m, "expireAt"));
        vo.setCreatedAt(MapHelper.dt(m, "createdAt"));
        return vo;
    }

    private static Map<String, Object> state(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 批量查询商品并补充卖家昵称（管理列表用；user-service 不可用时匿名） */
    public Map<Long, String> sellerNicknames(Set<Long> sellerIds) {
        Map<Long, String> map = new HashMap<>();
        if (sellerIds == null || sellerIds.isEmpty()) {
            return map;
        }
        try {
            List<Map<String, Object>> users = FeignResults.data(userClient.batchUsers(
                    sellerIds.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(","))), "用户服务");
            if (users != null) {
                for (Map<String, Object> user : users) {
                    Long id = MapHelper.lng(user, "id");
                    if (id != null) {
                        map.put(id, MapHelper.str(user, "nickname"));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[admin][item] 补充卖家昵称失败（容忍）：{}", e.getMessage());
        }
        return map;
    }

    /** 保留给未来列表内联补充使用（当前 item-service 若已返回 sellerNickname 则无需调用） */
    @SuppressWarnings("unused")
    private static Set<Long> collectSellerIds(List<AdminItemVO> list) {
        return list.stream().map(AdminItemVO::getSellerId).filter(Objects::nonNull).collect(Collectors.toCollection(HashSet::new));
    }
}
