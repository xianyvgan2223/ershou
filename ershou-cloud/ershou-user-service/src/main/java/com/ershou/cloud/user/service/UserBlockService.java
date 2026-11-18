package com.ershou.cloud.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.user.entity.Campus;
import com.ershou.cloud.user.entity.School;
import com.ershou.cloud.user.entity.SysUser;
import com.ershou.cloud.user.entity.UserBlock;
import com.ershou.cloud.user.mapper.CampusMapper;
import com.ershou.cloud.user.mapper.SchoolMapper;
import com.ershou.cloud.user.mapper.SysUserMapper;
import com.ershou.cloud.user.mapper.UserBlockMapper;
import com.ershou.cloud.user.vo.UserBlockVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 拉黑（从单体 `UserBlockService` 迁入，逻辑逐行保留）。
 *
 * <p>语义：**单向存储、双向生效**——A 拉黑 B 之后，双方都不能再发起会话/发消息
 * （chat-service 通过 `/inner/users/{id}/blocked?targetId=` 判定，不再直接读本库）。
 *
 * <p>微服务化改动只有"学校/校区名"：单体走 `DictionaryCache`（Redis 跨服务共享缓存，
 * 微服务禁止共享缓存实现），这里直接查本服务库的 `school`/`campus`（批量时一次查回）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBlockService {

    private final UserBlockMapper blockMapper;
    private final SysUserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;

    /** 拉黑（唯一索引兜住并发重复拉黑，重复即幂等） */
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
        if (userId == null) {
            return Set.of();
        }
        return blockMapper.selectList(Wrappers.<UserBlock>lambdaQuery()
                        .eq(UserBlock::getUserId, userId))
                .stream().map(UserBlock::getBlockedUserId).collect(Collectors.toSet());
    }

    /** 谁拉黑了我 */
    public Set<Long> blockerIds(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return blockMapper.selectList(Wrappers.<UserBlock>lambdaQuery()
                        .eq(UserBlock::getBlockedUserId, userId))
                .stream().map(UserBlock::getUserId).collect(Collectors.toSet());
    }

    /** 我拉黑了谁（带昵称/头像/信用/学校校区，可直接渲染列表） */
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

        // 学校/校区名：一次批量查回（黑名单通常只有几个人，避免 N+1）
        Map<Long, String> schoolNames = names(
                users.values().stream().map(SysUser::getSchoolId).filter(Objects::nonNull).collect(Collectors.toSet()),
                ids -> schoolMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(School::getId, School::getName)));
        Map<Long, String> campusNames = names(
                users.values().stream().map(SysUser::getCampusId).filter(Objects::nonNull).collect(Collectors.toSet()),
                ids -> campusMapper.selectBatchIds(ids).stream()
                        .collect(Collectors.toMap(Campus::getId, Campus::getName)));

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
                vo.setSchoolName(schoolNames.get(user.getSchoolId()));
                vo.setCampusName(campusNames.get(user.getCampusId()));
            }
            result.add(vo);
        }
        return result;
    }

    /** 批量取 id→name（ids 为空返回空 Map；查不到的 id 不出现在结果里） */
    private Map<Long, String> names(Collection<Long> ids, Function<Collection<Long>, Map<Long, String>> loader) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return new HashMap<>(loader.apply(ids));
    }
}
