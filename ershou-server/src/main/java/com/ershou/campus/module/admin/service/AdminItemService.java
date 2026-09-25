package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.admin.dto.BatchItemAuditDTO;
import com.ershou.campus.module.admin.dto.ItemRejectDTO;
import com.ershou.campus.module.admin.vo.AdminItemVO;
import com.ershou.campus.module.admin.vo.BatchAuditResultVO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.search.service.ItemIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 商品审核工作台：待审队列、单条/批量通过与驳回、违规强制下架。
 *
 * <p>状态流转全部用条件更新（{@code WHERE status = 1}）兜底，避免两个运营同时点"通过"造成重复处置。
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
    private static final String ACTION_APPROVE = "approve";
    private static final String ACTION_REJECT = "reject";

    private final ItemMapper itemMapper;
    private final ItemIndexService itemIndexService;
    private final AuditLogService auditLogService;
    private final AdminViewAssembler assembler;

    /** status 为空 = 全部状态（商品管理）；传 1 = 审核队列（按提交先后，先到先审） */
    public PageResult<AdminItemVO> page(Integer status, String keyword, Long sellerId,
                                        long pageNum, long pageSize) {
        LambdaQueryWrapper<Item> w = Wrappers.<Item>lambdaQuery();
        if (status != null) {
            w.eq(Item::getStatus, status);
        }
        if (sellerId != null) {
            w.eq(Item::getSellerId, sellerId);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(x -> x.like(Item::getTitle, kw).or().like(Item::getDescription, kw));
        }
        if (status != null && status == STATUS_AUDITING) {
            w.orderByAsc(Item::getCreatedAt);
        } else {
            w.orderByDesc(Item::getCreatedAt).orderByDesc(Item::getId);
        }
        Page<Item> page = itemMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return assembler.itemPage(page);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long itemId, Long operatorId) {
        Item before = itemMapper.selectById(itemId);
        if (before == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        int rows = itemMapper.approveAudit(itemId, APPROVE_REASON);
        if (rows == 0) {
            throw new BizException(ResultCode.ADMIN_ITEM_STATUS, "仅“审核中”的商品可以通过");
        }
        syncIndex(itemId);
        auditLogService.record(operatorId, "item", "approve", "item", itemId,
                state("status", before.getStatus(), "auditReason", before.getAuditReason()),
                state("status", STATUS_ON_SALE, "auditResult", 1, "auditReason", APPROVE_REASON));
        log.info("[admin][item][{}][approve][operator={}]", itemId, operatorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long itemId, ItemRejectDTO dto, Long operatorId) {
        Item before = itemMapper.selectById(itemId);
        if (before == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        String reason = AuditReasons.compose(AuditReasons.SCOPE_ITEM, dto.getReasonCode(), dto.getReason());
        int rows = itemMapper.rejectAudit(itemId, reason, dto.getReasonCode());
        if (rows == 0) {
            throw new BizException(ResultCode.ADMIN_ITEM_STATUS, "仅“审核中”的商品可以驳回");
        }
        syncIndex(itemId);
        auditLogService.record(operatorId, "item", "reject", "item", itemId,
                state("status", before.getStatus(), "auditReason", before.getAuditReason()),
                state("status", STATUS_REJECTED, "auditResult", 2,
                        "rejectCode", dto.getReasonCode(), "auditReason", reason));
        log.info("[admin][item][{}][reject][{}][operator={}]", itemId, dto.getReasonCode(), operatorId);
    }

    /** 批量通过/驳回：逐条条件更新，返回成功数与失败原因，不因单条失败整批回滚 */
    @Transactional(rollbackFor = Exception.class)
    public BatchAuditResultVO batch(BatchItemAuditDTO dto, Long operatorId) {
        String action = dto.getAction() == null ? "" : dto.getAction().trim().toLowerCase(Locale.ROOT);
        if (!ACTION_APPROVE.equals(action) && !ACTION_REJECT.equals(action)) {
            throw new BizException(ResultCode.PARAM_ERROR, "action 只能是 approve 或 reject");
        }
        String reason = ACTION_REJECT.equals(action)
                ? AuditReasons.compose(AuditReasons.SCOPE_ITEM, dto.getReasonCode(), dto.getReason())
                : null;
        List<Long> ids = dto.getIds().stream().filter(Objects::nonNull).distinct().toList();
        int success = 0;
        List<BatchAuditResultVO.Failure> failures = new ArrayList<>();
        for (Long id : ids) {
            Item item = itemMapper.selectById(id);
            if (item == null) {
                failures.add(new BatchAuditResultVO.Failure(id, "商品不存在"));
                continue;
            }
            if (item.getStatus() == null || item.getStatus() != STATUS_AUDITING) {
                failures.add(new BatchAuditResultVO.Failure(id,
                        "状态不是“审核中”（" + ItemService.statusText(item.getStatus()) + "）"));
                continue;
            }
            int rows = ACTION_APPROVE.equals(action)
                    ? itemMapper.approveAudit(id, APPROVE_REASON)
                    : itemMapper.rejectAudit(id, reason, dto.getReasonCode());
            if (rows == 1) {
                success++;
                syncIndex(id);
            } else {
                failures.add(new BatchAuditResultVO.Failure(id, "已被其他运营处理"));
            }
        }
        auditLogService.record(operatorId, "item", "batch-" + action, "item", null,
                state("ids", ids), state("success", success, "failed", failures.size(),
                        "reasonCode", dto.getReasonCode(), "reason", reason));
        log.info("[admin][item][batch-{}] success={} failed={} operator={}", action, success, failures.size(), operatorId);
        return new BatchAuditResultVO(success, failures.size(), failures);
    }

    /** 违规强制下架（不代表审核驳回，用于在售商品的事后处置） */
    @Transactional(rollbackFor = Exception.class)
    public void forceOffline(Long itemId, String reason, Long operatorId) {
        Item before = itemMapper.selectById(itemId);
        if (before == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        int rows = itemMapper.forceOffline(itemId, reason);
        if (rows == 0) {
            throw new BizException(ResultCode.ADMIN_ITEM_STATUS, "已售出/已超期/已强制下架的商品无需再下架");
        }
        syncIndex(itemId);
        auditLogService.record(operatorId, "item", "force-offline", "item", itemId,
                state("status", before.getStatus()), state("status", STATUS_VIOLATION, "reason", reason));
        log.info("[admin][item][{}][force-offline][operator={}]", itemId, operatorId);
    }

    private void syncIndex(Long itemId) {
        TxHooks.afterCommit(() -> itemIndexService.syncById(itemId));
    }

    private static Map<String, Object> state(Object... kv) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
