package com.ershou.campus.module.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.DictionaryCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.entity.UserBlock;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import com.ershou.campus.module.user.mapper.UserBlockMapper;
import com.ershou.campus.module.user.vo.UserBlockVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
    /** 直接把用户表 mapper 拿进来批量取昵称/头像，避免注入 UserService 绕出循环依赖 */
    private final SysUserMapper userMapper;
    private final DictionaryCache dict;

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

    /**
     * 我拉黑了谁（带昵称/头像等展示信息）。
     *
     * <p>注入 {@code SysUserMapper} 而不是 {@code UserService}：后者持有一堆别的依赖，
     * 直接注入容易牵扯出循环依赖；这里只需要"按 id 批量取用户"这一件事。
     */
    public List<UserBlockVO> list(Long userId) {
        List<UserBlock> blocks = blockMapper.selectList(Wrappers.<UserBlock>lambdaQuery()
                .eq(UserBlock::getUserId, userId)
                .orderByDesc(UserBlock::getId));
        if (blocks.isEmpty()) {
            return List.of();
        }

        Set<Long> blockedIds = blocks.stream()
                .map(UserBlock::getBlockedUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SysUser> users = blockedIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(blockedIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, Function.identity()));

        // 对方是否也拉黑了我：一次查出"谁拉黑了我"，避免在循环里逐条查
        Set<Long> myBlockers = blockerIds(userId);

        List<UserBlockVO> result = new ArrayList<>(blocks.size());
        for (UserBlock block : blocks) {
            UserBlockVO vo = new UserBlockVO();
            vo.setId(block.getId());
            vo.setBlockedUserId(block.getBlockedUserId());
            vo.setCreatedAt(block.getCreatedAt());
            vo.setBlockedByPeer(myBlockers.contains(block.getBlockedUserId()));

            SysUser user = users.get(block.getBlockedUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setCreditScore(user.getCreditScore());
                vo.setIdentityFlag(user.getIdentityFlag());
                vo.setShopName(user.getShopName());
                vo.setSchoolName(schoolName(user.getSchoolId()));
                vo.setCampusName(campusName(user.getCampusId()));
            }
            result.add(vo);
        }
        return result;
    }

    /** 校区/学校名走字典缓存（与 UserService.publicProfile 同一口径，不自己查库） */
    private String schoolName(Long schoolId) {
        if (schoolId == null) {
            return null;
        }
        var school = dict.school(schoolId);
        return school == null ? null : school.getName();
    }

    private String campusName(Long campusId) {
        if (campusId == null) {
            return null;
        }
        var campus = dict.campus(campusId);
        return campus == null ? null : campus.getName();
    }
}
