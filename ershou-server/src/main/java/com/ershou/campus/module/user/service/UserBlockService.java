package com.ershou.campus.module.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.user.entity.UserBlock;
import com.ershou.campus.module.user.mapper.UserBlockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 拉黑（PRD FR-UP-04）：单向存储、双向生效——A 拉黑 B 之后，
 * 双方都不能再发起会话/发消息（避免"被拉黑的人还能继续骚扰"）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBlockService {

    private final UserBlockMapper blockMapper;

    public void block(Long userId, Long targetId) {
        if (targetId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户不存在");
        }
        if (Objects.equals(userId, targetId)) {
            throw new BizException(ResultCode.ADMIN_SELF_OPERATION, "不能拉黑自己");
        }
        try {
            UserBlock block = new UserBlock();
            block.setUserId(userId);
            block.setBlockedUserId(targetId);
            blockMapper.insert(block);
            log.info("[user][block][{}]->[{}]", userId, targetId);
        } catch (DuplicateKeyException e) {
            // 已拉黑过，幂等
            log.debug("[user][block][{}]->[{}] 重复拉黑，忽略", userId, targetId);
        }
    }

    public void unblock(Long userId, Long targetId) {
        int deleted = blockMapper.delete(Wrappers.<UserBlock>lambdaQuery()
                .eq(UserBlock::getUserId, userId)
                .eq(UserBlock::getBlockedUserId, targetId));
        if (deleted > 0) {
            log.info("[user][unblock][{}]->[{}]", userId, targetId);
        }
    }

    /** userId 是否拉黑了 targetId */
    public boolean blocked(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return false;
        }
        return blockMapper.selectCount(Wrappers.<UserBlock>lambdaQuery()
                .eq(UserBlock::getUserId, userId)
                .eq(UserBlock::getBlockedUserId, targetId)) > 0;
    }

    /** 两人之间是否存在任一方向的拉黑 */
    public boolean blockedEither(Long userA, Long userB) {
        if (userA == null || userB == null || Objects.equals(userA, userB)) {
            return false;
        }
        return blockMapper.selectCount(Wrappers.<UserBlock>lambdaQuery()
                .and(w -> w.and(x -> x.eq(UserBlock::getUserId, userA).eq(UserBlock::getBlockedUserId, userB))
                        .or(x -> x.eq(UserBlock::getUserId, userB).eq(UserBlock::getBlockedUserId, userA)))) > 0;
    }

    /** 我拉黑了谁 */
    public Set<Long> blockedUserIds(Long userId) {
        return blockMapper.selectList(Wrappers.<UserBlock>lambdaQuery()
                        .eq(UserBlock::getUserId, userId))
                .stream().map(UserBlock::getBlockedUserId).collect(Collectors.toSet());
    }

    /** 谁拉黑了我 */
    public Set<Long> blockerIds(Long userId) {
        return blockMapper.selectList(Wrappers.<UserBlock>lambdaQuery()
                        .eq(UserBlock::getBlockedUserId, userId))
                .stream().map(UserBlock::getUserId).collect(Collectors.toSet());
    }

    public List<UserBlock> list(Long userId) {
        return blockMapper.selectList(Wrappers.<UserBlock>lambdaQuery()
                .eq(UserBlock::getUserId, userId)
                .orderByDesc(UserBlock::getId));
    }
}
