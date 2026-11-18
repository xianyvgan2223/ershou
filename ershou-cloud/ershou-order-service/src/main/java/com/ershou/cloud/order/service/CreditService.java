package com.ershou.cloud.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.order.dto.CreditLogRecordDTO;
import com.ershou.cloud.order.dto.CreditLogVO;
import com.ershou.cloud.order.entity.CreditLog;
import com.ershou.cloud.order.feign.InnerUserVO;
import com.ershou.cloud.order.feign.UserClient;
import com.ershou.cloud.order.mapper.CreditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 信用分服务（第 103 轮：{@code credit_log} 归 order-service，score 归 user-service）。
 *
 * <p>设计取舍（"写自己的库"）：
 * <ul>
 *   <li>本服务只写 {@code ershou_order.credit_log} 流水；</li>
 *   <li>真正改 {@code sys_user.credit_score} 走 Feign {@code POST /inner/users/{id}/credit?delta=}；</li>
 *   <li>user-service 不可用（迁移期/宕机）时降级：仍写流水（before/after 按用户服务返回值或初始 100 推算），
 *       log.warn 标记 degraded —— 分数与流水可能短暂不一致，恢复后由对账脚本修正（遗留项，报告中说明）。</li>
 * </ul>
 *
 * <p>面交完成加分类似旧单体：买卖双方各 +2（平台账号不加分）；运营人工调整由 admin-service 调
 * {@code POST /inner/credit-logs} 留痕（它自己写 user-service 改分）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditService {

    private static final int DEFAULT_SCORE = 100;

    private final CreditLogMapper creditLogMapper;
    private final UserClient userClient;

    /**
     * 信用分增减：先查当前分 → 调 user-service 改分 → 写流水。
     *
     * @param delta     正负分（面交完成 +2）
     * @param reason    原因文案（进 credit_log）
     * @param bizType   order / admin
     * @param bizId     关联业务 id（面交单 id）
     * @param operatorId 操作人（admin 人工调整时）
     */
    @Transactional(rollbackFor = Exception.class)
    public void addCredit(Long userId, int delta, String reason, String bizType, Long bizId, Long operatorId) {
        if (userId == null || delta == 0) {
            return;
        }
        Integer before = null;
        try {
            Result<InnerUserVO> r = userClient.get(userId);
            if (r != null && r.getCode() == ResultCode.SUCCESS.getCode() && r.getData() != null) {
                before = r.getData().getCreditScore();
            }
        } catch (Exception e) {
            log.warn("[credit][degrade] 查询用户 {} 信用分失败: {}", userId, e.getMessage());
        }
        if (before == null) {
            before = DEFAULT_SCORE; // 降级：用户服务不可用按初始分推算，仅保证流水可读
        }

        boolean scoreUpdated = false;
        try {
            Result<Integer> r = userClient.addCredit(userId, delta, reason);
            scoreUpdated = r != null && r.getCode() == ResultCode.SUCCESS.getCode()
                    && r.getData() != null && r.getData() > 0;
        } catch (Exception e) {
            log.warn("[credit][degrade] 用户服务加分失败 user={} delta={}: {}", userId, delta, e.getMessage());
        }
        int after = Math.max(0, before + delta);
        if (!scoreUpdated) {
            log.warn("[credit][degrade] user-service 未改分（迁移期/未实现），仅记流水：user={} delta={} before={} after≈{}",
                    userId, delta, before, after);
        }

        CreditLog cl = new CreditLog();
        cl.setUserId(userId);
        cl.setChangeValue(delta);
        cl.setBeforeScore(before);
        cl.setAfterScore(after);
        cl.setReason(reason);
        cl.setBizType(bizType);
        cl.setBizId(bizId);
        cl.setOperatorId(operatorId);
        creditLogMapper.insert(cl);
        log.info("[credit][add] user={} delta={} before={} after={} bizType={} bizId={} scoreUpdated={}",
                userId, delta, before, after, bizType, bizId, scoreUpdated);
    }

    /** 我的信用分流水 / admin 查用户流水 */
    public PageResult<CreditLogVO> page(Long userId, long pageNum, long pageSize) {
        Page<CreditLog> page = creditLogMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<CreditLog>lambdaQuery().eq(CreditLog::getUserId, userId)
                        .orderByDesc(CreditLog::getId));
        return PageResult.of(page, this::toVO);
    }

    /**
     * 内部：运营人工调整后补流水（admin-service 先改 user-service 分数，再调本服务留痕）。
     * 幂等性由调用方保证（同一操作不重复调用）。
     */
    public void recordAdminAdjust(CreditLogRecordDTO dto) {
        CreditLog cl = new CreditLog();
        cl.setUserId(dto.getUserId());
        cl.setChangeValue(dto.getChangeValue());
        cl.setBeforeScore(dto.getBeforeScore());
        cl.setAfterScore(dto.getAfterScore());
        cl.setReason(dto.getReason());
        cl.setBizType(dto.getBizType() == null ? "admin" : dto.getBizType());
        cl.setBizId(dto.getBizId());
        cl.setOperatorId(dto.getOperatorId());
        creditLogMapper.insert(cl);
        log.info("[credit][admin] user={} change={} reason={} operator={}",
                dto.getUserId(), dto.getChangeValue(), dto.getReason(), dto.getOperatorId());
    }

    private CreditLogVO toVO(CreditLog c) {
        CreditLogVO vo = new CreditLogVO();
        vo.setId(c.getId());
        vo.setUserId(c.getUserId());
        vo.setChangeValue(c.getChangeValue());
        vo.setBeforeScore(c.getBeforeScore());
        vo.setAfterScore(c.getAfterScore());
        vo.setReason(c.getReason());
        vo.setBizType(c.getBizType());
        vo.setBizId(c.getBizId());
        vo.setOperatorId(c.getOperatorId());
        vo.setCreatedAt(c.getCreatedAt());
        return vo;
    }
}
