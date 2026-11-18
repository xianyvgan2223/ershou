package com.ershou.cloud.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.chat.config.ChatProperties;
import com.ershou.cloud.chat.entity.ChatMessage;
import com.ershou.cloud.chat.entity.ChatSession;
import com.ershou.cloud.chat.feign.BlockRelationVO;
import com.ershou.cloud.chat.feign.ItemClient;
import com.ershou.cloud.chat.feign.ItemVO;
import com.ershou.cloud.chat.feign.UserClient;
import com.ershou.cloud.chat.feign.UserVO;
import com.ershou.cloud.chat.mapper.ChatMessageMapper;
import com.ershou.cloud.chat.mapper.ChatSessionMapper;
import com.ershou.cloud.chat.util.ChatRedisKeys;
import com.ershou.cloud.chat.util.ContentCipher;
import com.ershou.cloud.chat.vo.BatchAuditResultVO;
import com.ershou.cloud.chat.vo.ChatMessageVO;
import com.ershou.cloud.chat.vo.ChatSessionVO;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 私聊：会话 + 消息 + 未读（从旧单体 ChatService 原样迁移，跨模块数据改走 Feign）。
 *
 * <p>几条边界（与单体保持一致）：
 * <ul>
 *   <li>未读数以 DB 为权威（chat_session.unread_a/unread_b 条件自增），
 *       Redis Hash 只是顶栏红点的快速读取路径，读会话列表时用 DB 值回写自愈；</li>
 *   <li>文本正文 AES 加密落库（与单体同 key，历史消息可解密），图片/卡片类存原文；</li>
 *   <li>每用户软删除 deleted_a/deleted_b：对方再发消息时自动恢复；</li>
 *   <li>跨模块数据：对方昵称/头像/信用/身份 → user-service Feign（/inner/users/**）；
 *       商品卡片 → item-service Feign（/inner/items/{id}）；拉黑关系 → user-service Feign。
 *       任一依赖降级只影响展示，不影响会话/消息主链路。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final int MSG_TEXT = 1;
    public static final int MSG_IMAGE = 2;

    private static final int RISK_NONE = 0;
    private static final String PLACEHOLDER = "[消息内容不可用]";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final UserClient userClient;
    private final ItemClient itemClient;
    private final ChatPushService pushService;
    private final ContentCipher cipher;
    private final StringRedisTemplate redis;
    private final ChatProperties props;

    // ------------------------------------------------------------ 会话

    /** 从商品详情发起点对点会话：同一对人 + 同一件商品只有一条会话（唯一索引兜底并发） */
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO openSession(Long userId, Long itemId) {
        ItemVO item = item(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        Long sellerId = item.getSellerId();
        if (sellerId == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        if (Objects.equals(sellerId, userId)) {
            throw new BizException(ResultCode.CHAT_SELF);
        }
        if (blockedEither(userId, sellerId)) {
            throw new BizException(ResultCode.CHAT_BLOCKED);
        }
        long a = Math.min(userId, sellerId);
        long b = Math.max(userId, sellerId);

        ChatSession session = findSession(a, b, itemId);
        if (session == null) {
            ChatSession created = new ChatSession();
            created.setUserA(a);
            created.setUserB(b);
            created.setItemId(itemId);
            created.setUnreadA(0);
            created.setUnreadB(0);
            created.setRiskLevel(RISK_NONE);
            created.setStatus(1);
            created.setDeletedA(0);
            created.setDeletedB(0);
            // 新会话排到列表最前面
            created.setLastMsgAt(LocalDateTime.now());
            try {
                sessionMapper.insert(created);
                session = created;
            } catch (DuplicateKeyException e) {
                // 双方同时点"私聊"：唯一索引拦住，回查已存在的那条
                session = findSession(a, b, itemId);
                if (session == null) {
                    throw new BizException(ResultCode.SYSTEM_ERROR, "会话创建失败，请重试");
                }
            }
        } else if (session.deletedFor(userId)) {
            // 之前删过这个会话，现在又主动从商品页点进来 —— 视为"重新开启"，撤销删除
            sessionMapper.restoreDeleted(session.getId());
            log.info("[chat][open][{}][{}] 重新开启已删除的会话", userId, session.getId());
            session = sessionMapper.selectById(session.getId());
        }
        BlockSets blocks = blockSets(userId, Set.of(sellerId));
        return toSessionVO(session, userId, Map.of(itemId, item), users(Set.of(sellerId)),
                blocks.myBlocks(), blocks.myBlockers());
    }

    /**
     * 删除聊天记录（每用户软删除）：只标记我这一侧，未读一并归零；对方的会话列表不受影响。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, Long sessionId) {
        ChatSession session = requireMember(sessionId, userId);
        boolean sinceA = Objects.equals(session.getUserA(), userId);
        if (session.deletedFor(userId)) {
            log.debug("[chat][delete][{}][{}] 已是删除状态，忽略", userId, sessionId);
            return;
        }
        sessionMapper.markDeleted(sessionId, sinceA);
        // 未读快照一起清：DB 已经归零，Redis 里留着就会算进顶栏红点
        try {
            redis.opsForHash().delete(ChatRedisKeys.chatUnread(userId), String.valueOf(sessionId));
        } catch (Exception e) {
            log.warn("[chat][delete][{}] 清理 Redis 未读快照失败：{}", userId, e.getMessage());
        }
        log.info("[chat][delete][{}][{}] 已从本人列表删除会话（对方不受影响）", userId, sessionId);
    }

    /** 批量删除：逐条独立处理，不因单条失败整批回滚（与单体口径一致）。 */
    @Transactional(rollbackFor = Exception.class)
    public BatchAuditResultVO batchDelete(Long userId, List<Long> rawIds) {
        List<Long> ids = rawIds == null ? List.of() : rawIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请先勾选要删除的会话");
        }
        int success = 0;
        List<BatchAuditResultVO.Failure> failures = new ArrayList<>();
        for (Long sessionId : ids) {
            try {
                deleteSession(userId, sessionId);
                success++;
            } catch (BizException e) {
                failures.add(new BatchAuditResultVO.Failure(sessionId, e.getMessage()));
            } catch (Exception e) {
                log.warn("[chat][batch-delete][{}][{}] 删除失败：{}", userId, sessionId, e.getMessage());
                failures.add(new BatchAuditResultVO.Failure(sessionId, "删除失败：" + e.getMessage()));
            }
        }
        log.info("[chat][batch-delete][{}] success={} failed={}", userId, success, failures.size());
        return new BatchAuditResultVO(success, failures.size(), failures);
    }

    /** 我的会话列表（默认剔除我删过的会话） */
    public List<ChatSessionVO> listSessions(Long userId) {
        return listSessions(userId, false);
    }

    public List<ChatSessionVO> listSessions(Long userId, boolean includeDeleted) {
        List<ChatSession> sessions = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                .and(w -> w.eq(ChatSession::getUserA, userId).or().eq(ChatSession::getUserB, userId))
                .orderByDesc(ChatSession::getLastMsgAt)
                .orderByDesc(ChatSession::getId));
        if (sessions.isEmpty()) {
            return List.of();
        }
        if (!includeDeleted) {
            sessions = sessions.stream().filter(s -> !s.deletedFor(userId)).toList();
            if (sessions.isEmpty()) {
                return List.of();
            }
        }
        Set<Long> peerIds = sessions.stream().map(s -> s.peerOf(userId)).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> itemIds = sessions.stream().map(ChatSession::getItemId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserVO> peers = users(peerIds);
        Map<Long, ItemVO> items = items(itemIds);
        // 用 DB（权威）刷新 Redis 未读快照：Redis 丢数据或被改脏都能自愈
        syncUnreadCache(userId, sessions);
        // 拉黑关系一次查全（我拉黑的 + 拉黑我的），会话列表里标注能否继续发消息
        BlockSets blocks = blockSets(userId, peerIds);
        return sessions.stream().map(s -> toSessionVO(s, userId, items, peers,
                blocks.myBlocks(), blocks.myBlockers())).toList();
    }

    /** /inner/chat/sessions 用：分页返回会话列表（管理端/用户页） */
    public PageResult<ChatSessionVO> pageSessions(Long userId, long page, long pageSize) {
        long p = Math.max(1, page);
        long size = Math.min(Math.max(1, pageSize), 100);
        List<ChatSessionVO> all = listSessions(userId, false);
        int from = (int) Math.min(all.size(), (p - 1) * size);
        int to = (int) Math.min(all.size(), from + size);
        return PageResult.of(all.subList(from, to), all.size(), p, size);
    }

    public ChatSessionVO sessionDetail(Long userId, Long sessionId) {
        ChatSession session = requireMember(sessionId, userId);
        Map<Long, ItemVO> items = session.getItemId() == null ? Map.of()
                : Optional.ofNullable(item(session.getItemId()))
                        .map(i -> Map.of(i.getId(), i)).orElse(Map.of());
        Long peerId = session.peerOf(userId);
        BlockSets blocks = blockSets(userId, peerId == null ? Set.of() : Set.of(peerId));
        return toSessionVO(session, userId, items, peerMap(peerId), blocks.myBlocks(), blocks.myBlockers());
    }

    /** 标记已读：DB 归零 + Redis 快照删除 + 通知对方"已读" */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long sessionId) {
        ChatSession session = requireMember(sessionId, userId);
        if (Objects.equals(session.getUserA(), userId)) {
            sessionMapper.clearUnreadA(sessionId);
        } else {
            sessionMapper.clearUnreadB(sessionId);
        }
        try {
            redis.opsForHash().delete(ChatRedisKeys.chatUnread(userId), String.valueOf(sessionId));
        } catch (Exception e) {
            log.warn("[chat][read][{}] 清理 Redis 未读失败：{}", userId, e.getMessage());
        }
        Long peerId = session.peerOf(userId);
        if (peerId != null) {
            pushService.push(peerId, "read", Map.of("sessionId", sessionId, "readerId", userId));
        }
    }

    /** 未读汇总：顶栏红点读 Redis 快照，快照不存在/有僵尸字段时回源 DB 自愈。 */
    public Map<String, Object> unread(Long userId) {
        Map<String, Integer> bySession = new LinkedHashMap<>();
        List<ChatSession> sessions = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                        .and(w -> w.eq(ChatSession::getUserA, userId).or().eq(ChatSession::getUserB, userId)))
                .stream().filter(s -> !s.deletedFor(userId)).toList();
        Map<Long, ChatSession> byId = sessions.stream()
                .collect(Collectors.toMap(ChatSession::getId, s -> s, (a, b) -> a));

        Map<Object, Object> cached = Map.of();
        try {
            cached = redis.opsForHash().entries(ChatRedisKeys.chatUnread(userId));
        } catch (Exception e) {
            log.warn("[chat][unread][{}] 读取 Redis 快照失败，回源 DB：{}", userId, e.getMessage());
        }

        if (cached.isEmpty()) {
            syncUnreadCache(userId, sessions);
            for (ChatSession s : sessions) {
                bySession.put(String.valueOf(s.getId()), s.unreadOf(userId));
            }
        } else {
            Set<Object> stale = new HashSet<>();
            cached.forEach((k, v) -> {
                long sid;
                try {
                    sid = Long.parseLong(String.valueOf(k));
                } catch (NumberFormatException e) {
                    stale.add(k);
                    return;
                }
                ChatSession s = byId.get(sid);
                if (s == null) {
                    stale.add(k); // 会话已被删除：清掉僵尸字段，不要计入未读
                    return;
                }
                bySession.put(String.valueOf(sid), s.unreadOf(userId));
            });
            if (!stale.isEmpty()) {
                try {
                    redis.opsForHash().delete(ChatRedisKeys.chatUnread(userId), stale.toArray());
                    log.info("[chat][unread][{}] 清掉 {} 个已删除会话的僵尸未读字段", userId, stale.size());
                } catch (Exception e) {
                    log.warn("[chat][unread][{}] 清理僵尸未读字段失败：{}", userId, e.getMessage());
                }
            }
            for (ChatSession s : sessions) {
                bySession.putIfAbsent(String.valueOf(s.getId()), s.unreadOf(userId));
            }
        }

        int total = bySession.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("sessions", bySession);
        return result;
    }

    // ------------------------------------------------------------ 消息

    /** 历史消息：id 游标，返回时间正序。beforeId 往前翻，afterId 断线补拉，两者互斥。 */
    public List<ChatMessageVO> listMessages(Long userId, Long sessionId, Long beforeId, Long afterId, Integer size) {
        requireMember(sessionId, userId);
        if (beforeId != null && afterId != null) {
            throw new BizException(ResultCode.PARAM_ERROR, "beforeId 与 afterId 不能同时使用");
        }
        int limit = size == null || size < 1 ? props.getHistorySize()
                : Math.min(size, props.getMaxHistorySize());
        LambdaQueryWrapper<ChatMessage> query = Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                // 被风控拦截的消息只对发送方可见，接收方读不到
                .and(w -> w.eq(ChatMessage::getBlocked, 0).or().eq(ChatMessage::getSenderId, userId));
        if (afterId != null) {
            query.gt(ChatMessage::getId, afterId);
        } else {
            query.lt(beforeId != null, ChatMessage::getId, beforeId);
        }
        List<ChatMessage> rows = messageMapper.selectList(
                query.orderByDesc(ChatMessage::getId).last("LIMIT " + limit));
        List<ChatMessageVO> list = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            list.add(toMessageVO(rows.get(i), userId, null));
        }
        return list;
    }

    /** 发消息：限额 → 落库 → 会话摘要/未读 → 推送（落库成功才返回，前端据此收 ACK） */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO send(Long senderId, Long sessionId, Integer msgType, String rawContent, String clientMsgId) {
        ChatSession session = requireMember(sessionId, senderId);
        Long receiverId = session.peerOf(senderId);
        if (blockedEither(senderId, receiverId)) {
            // 双向拦截：被拉黑的人也不能继续发（否则拉黑形同虚设）
            throw new BizException(ResultCode.CHAT_BLOCKED);
        }
        int type = msgType == null ? MSG_TEXT : msgType;
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty()) {
            throw new BizException(ResultCode.CHAT_CONTENT_INVALID, "消息内容不能为空");
        }
        if (type == MSG_TEXT && content.length() > props.getMaxTextLength()) {
            throw new BizException(ResultCode.CHAT_CONTENT_INVALID,
                    "单条消息最多 " + props.getMaxTextLength() + " 字");
        }

        checkDailyLimit(senderId);

        // 关键词机审在单体阶段已移除：消息直接放行（riskLevel 保留字段以兼容历史数据）
        int riskLevel = RISK_NONE;

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setItemId(session.getItemId());
        message.setMsgType(type);
        message.setContent(type == MSG_TEXT ? cipher.encrypt(content) : content);
        message.setRiskLevel(riskLevel);
        message.setBlocked(0);
        messageMapper.insert(message);

        ChatSession update = new ChatSession();
        update.setId(sessionId);
        update.setLastMsgId(message.getId());
        update.setLastMsgText(preview(type, content));
        update.setLastMsgAt(LocalDateTime.now());
        if (riskLevel > nz(session.getRiskLevel())) {
            update.setRiskLevel(riskLevel);
        }
        sessionMapper.updateById(update);
        // 对方删过这个会话的话，把它恢复出来 —— 否则消息落库了、对方却永远看不到
        if (session.deletedFor(receiverId)) {
            sessionMapper.restoreDeleted(sessionId);
            log.info("[chat][send][{}] 接收方此前删除了会话，已自动恢复", sessionId);
        }
        incrementUnread(session, receiverId);

        ChatMessageVO vo = toMessageVO(message, senderId, clientMsgId);
        // 推给对方：本机直推 + 跨实例广播
        pushService.push(receiverId, "message", toMessageVO(message, receiverId, null));
        log.info("[chat][send][{}->{}] session={} type={} risk={}", senderId, receiverId, sessionId, type, riskLevel);
        return vo;
    }

    /** 撤回：仅发送方、限时（默认 2 分钟）、幂等；撤回后推给对端让其同步消失 */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO recall(Long userId, Long messageId) {
        ChatMessage message = messageMapper.selectById(messageId);
        if (message == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "消息不存在");
        }
        ChatSession session = requireMember(message.getSessionId(), userId);
        if (!Objects.equals(message.getSenderId(), userId)) {
            throw new BizException(ResultCode.CHAT_RECALL_DENIED, "只能撤回自己发送的消息");
        }
        if (message.getRecalled() != null && message.getRecalled() == 1) {
            return toMessageVO(message, userId, null);
        }
        LocalDateTime deadline = LocalDateTime.now().minus(props.getRecallWindow());
        if (message.getCreatedAt() != null && message.getCreatedAt().isBefore(deadline)) {
            throw new BizException(ResultCode.CHAT_RECALL_DENIED,
                    "只能撤回 " + props.getRecallWindow().toMinutes() + " 分钟内发送的消息");
        }
        ChatMessage update = new ChatMessage();
        update.setId(messageId);
        update.setRecalled(1);
        messageMapper.updateById(update);
        message.setRecalled(1);

        // 撤回的正好是最后一条 → 会话列表预览同步改掉
        if (Objects.equals(session.getLastMsgId(), messageId)) {
            ChatSession sessionUpdate = new ChatSession();
            sessionUpdate.setId(session.getId());
            sessionUpdate.setLastMsgText("[已撤回]");
            sessionMapper.updateById(sessionUpdate);
        }
        ChatMessageVO vo = toMessageVO(message, userId, null);
        Long peerId = session.peerOf(userId);
        if (peerId != null) {
            pushService.push(peerId, "recall", toMessageVO(message, peerId, null));
        }
        log.info("[chat][recall][{}] message={} session={}", userId, messageId, session.getId());
        return vo;
    }

    public List<String> quickReplies() {
        return props.getQuickReplies();
    }

    /** 当前用户在本实例上的私聊连接数（排查用） */
    public Map<String, Object> status(Long userId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("instanceId", pushService.instanceId());
        map.put("localSessions", pushService.localSessionCount(userId));
        map.put("online", online(userId));
        map.put("enabled", props.isEnabled());
        return map;
    }

    // ------------------------------------------------------------ 内部

    private ChatSession findSession(long userA, long userB, Long itemId) {
        return sessionMapper.selectOne(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getUserA, userA)
                .eq(ChatSession::getUserB, userB)
                .eq(ChatSession::getItemId, itemId));
    }

    private ChatSession requireMember(Long sessionId, Long userId) {
        if (sessionId == null) {
            throw new BizException(ResultCode.CHAT_NOT_FOUND);
        }
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.contains(userId)) {
            // 不区分"不存在"和"不是参与者"，避免探测他人会话 id
            throw new BizException(ResultCode.CHAT_NOT_FOUND);
        }
        return session;
    }

    /**
     * 新号限额：注册 newUserDays 天内每日私聊不超过 sys_user.chat_limit 条。
     * 默认关闭；user-service 未返回 createdAt/chatLimit 时放行（可用性优先）。
     */
    private void checkDailyLimit(Long userId) {
        if (!props.isDailyLimitEnabled()) {
            return;
        }
        UserVO user = user(userId);
        if (user == null || user.getCreatedAt() == null) {
            return;
        }
        boolean newUser = user.getCreatedAt().isAfter(LocalDateTime.now().minusDays(props.getNewUserDays()));
        if (!newUser) {
            return;
        }
        int limit = user.getChatLimit() == null ? 20 : user.getChatLimit();
        try {
            String key = ChatRedisKeys.chatLimit(userId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 到期时间为当天 23:59:59
                redis.expire(key, Duration.between(LocalDateTime.now(),
                        LocalDate.now().plusDays(1).atStartOfDay()).plusSeconds(1));
            }
            if (count != null && count > limit) {
                throw new BizException(ResultCode.CHAT_LIMIT,
                        "新账号每日最多私聊 " + limit + " 条，明天再来吧");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // Redis 挂了不做限额（可用性优先，限额不是正确性约束）
            log.warn("[chat][limit][{}] 限额计数失败，本次放行：{}", userId, e.getMessage());
        }
    }

    /** 未读 +1：DB 条件自增（权威）+ Redis 快照自增（读路径），并发发消息不丢计数 */
    private void incrementUnread(ChatSession session, Long receiverId) {
        if (Objects.equals(session.getUserA(), receiverId)) {
            sessionMapper.incrUnreadA(session.getId());
        } else {
            sessionMapper.incrUnreadB(session.getId());
        }
        try {
            String key = ChatRedisKeys.chatUnread(receiverId);
            redis.opsForHash().increment(key, String.valueOf(session.getId()), 1L);
            redis.expire(key, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("[chat][unread][{}] Redis 快照自增失败（DB 已记）：{}", receiverId, e.getMessage());
        }
    }

    private void syncUnreadCache(Long userId, List<ChatSession> sessions) {
        try {
            String key = ChatRedisKeys.chatUnread(userId);
            Map<String, String> values = new HashMap<>(sessions.size());
            for (ChatSession s : sessions) {
                values.put(String.valueOf(s.getId()), String.valueOf(s.unreadOf(userId)));
            }
            if (values.isEmpty()) {
                return;
            }
            redis.opsForHash().putAll(key, values);
            redis.expire(key, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("[chat][unread][{}] 刷新 Redis 快照失败：{}", userId, e.getMessage());
        }
    }

    private Map<Long, UserVO> peerMap(Long peerId) {
        return peerId == null ? Map.of() : users(Set.of(peerId));
    }

    private ChatSessionVO toSessionVO(ChatSession session, Long userId, Map<Long, ItemVO> items,
                                      Map<Long, UserVO> peers, Set<Long> myBlocks, Set<Long> myBlockers) {
        Long peerId = session.peerOf(userId);
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(session.getId());
        vo.setLastMsgId(session.getLastMsgId());
        vo.setPeerId(peerId);
        vo.setLastMsgText(session.getLastMsgText());
        vo.setLastMsgAt(session.getLastMsgAt());
        vo.setUnread(session.unreadOf(userId));
        vo.setRiskLevel(session.getRiskLevel());
        vo.setCreatedAt(session.getCreatedAt());
        UserVO peer = peerId == null ? null : peers.get(peerId);
        if (peer != null) {
            vo.setPeerName(peer.getNickname());
            vo.setPeerAvatar(peer.getAvatar());
            vo.setPeerCreditScore(peer.getCreditScore());
            vo.setPeerIdentityFlag(peer.getIdentityFlag());
        }
        vo.setPeerOnline(peerId != null && online(peerId));
        boolean blockedByMe = peerId != null && myBlocks.contains(peerId);
        boolean blockedByPeer = peerId != null && myBlockers.contains(peerId);
        vo.setBlockedByMe(blockedByMe);
        vo.setBlockedByPeer(blockedByPeer);
        vo.setCanSend(!blockedByMe && !blockedByPeer
                && (session.getStatus() == null || session.getStatus() == 1));
        ItemVO item = session.getItemId() == null ? null : items.get(session.getItemId());
        if (item != null) {
            vo.setItemId(item.getId());
            vo.setItemTitle(item.getTitle());
            vo.setItemCover(item.getCoverUrl());
            vo.setItemPrice(item.getPrice());
            vo.setItemStatus(item.getStatus());
            vo.setItemStatusText(statusText(item.getStatus()));
            // 角色是相对"来源商品"而言的：谁是卖家一目了然
            boolean iAmSeller = Objects.equals(item.getSellerId(), userId);
            vo.setMyRole(iAmSeller ? "seller" : "buyer");
            vo.setPeerRole(iAmSeller ? "buyer" : "seller");
        } else if (session.getItemId() != null) {
            vo.setItemId(session.getItemId());
            vo.setItemTitle("商品已删除");
        }
        return vo;
    }

    private ChatMessageVO toMessageVO(ChatMessage message, Long viewerId, String clientMsgId) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setSenderId(message.getSenderId());
        vo.setReceiverId(message.getReceiverId());
        vo.setMsgType(message.getMsgType());
        vo.setRiskLevel(message.getRiskLevel());
        vo.setClientMsgId(clientMsgId);
        vo.setMine(Objects.equals(message.getSenderId(), viewerId));
        vo.setCreatedAt(message.getCreatedAt());
        boolean recalled = message.getRecalled() != null && message.getRecalled() == 1;
        vo.setRecalled(recalled);
        if (recalled) {
            // 撤回后连发送方也不再下发原文，避免"撤回了个寂寞"
            vo.setContent("");
            return vo;
        }
        String content = message.getMsgType() != null && message.getMsgType() == MSG_TEXT
                ? cipher.decrypt(message.getContent())
                : message.getContent();
        vo.setContent(content == null ? PLACEHOLDER : content);
        return vo;
    }

    private String preview(int msgType, String content) {
        if (msgType == MSG_IMAGE) {
            return "[图片]";
        }
        if (msgType != MSG_TEXT) {
            return "[消息]";
        }
        int max = props.getPreviewLength();
        return content.length() <= max ? content : content.substring(0, max) + "…";
    }

    private boolean online(Long userId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(ChatRedisKeys.chatOnline(userId)));
        } catch (Exception e) {
            return false;
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 商品状态文案（与旧单体 ItemService.statusText 完全一致） */
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

    // ------------------------------------------------------------ Feign 封装（降级不影响主链路）

    private static boolean ok(Result<?> r) {
        return r != null && r.getCode() == ResultCode.SUCCESS.getCode();
    }

    private static String joinIds(Collection<Long> ids) {
        return ids.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(","));
    }

    private ItemVO item(Long itemId) {
        if (itemId == null) {
            return null;
        }
        try {
            Result<ItemVO> r = itemClient.get(itemId);
            if (ok(r)) {
                return r.getData();
            }
        } catch (Exception e) {
            log.warn("[chat][feign][item][{}] 获取失败：{}", itemId, e.getMessage());
        }
        return null;
    }

    /** 批量取商品：优先契约 batch；接口未就绪/失败则回退逐个 get（item-service 分批建设中）。 */
    private Map<Long, ItemVO> items(Collection<Long> ids) {
        List<Long> list = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<Long, ItemVO> map = new HashMap<>();
        try {
            Result<List<ItemVO>> r = itemClient.batch(joinIds(list));
            if (ok(r) && r.getData() != null) {
                r.getData().stream().filter(Objects::nonNull)
                        .forEach(i -> map.put(i.getId(), i));
                return map;
            }
        } catch (Exception e) {
            log.debug("[chat][feign][item] 批量接口不可用，回退逐个：{}", e.getMessage());
        }
        for (Long id : list) {
            ItemVO vo = item(id);
            if (vo != null) {
                map.put(id, vo);
            }
        }
        return map;
    }

    private UserVO user(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<UserVO> r = userClient.get(userId);
            if (ok(r)) {
                return r.getData();
            }
        } catch (Exception e) {
            log.warn("[chat][feign][user][{}] 获取失败：{}", userId, e.getMessage());
        }
        return null;
    }

    /** 批量取用户：优先 batch；接口未就绪/失败则回退逐个 get。 */
    private Map<Long, UserVO> users(Collection<Long> ids) {
        List<Long> list = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserVO> map = new HashMap<>();
        try {
            Result<List<UserVO>> r = userClient.batch(joinIds(list));
            if (ok(r) && r.getData() != null) {
                r.getData().stream().filter(Objects::nonNull)
                        .forEach(u -> map.put(u.getId(), u));
                return map;
            }
        } catch (Exception e) {
            log.debug("[chat][feign][user] 批量接口不可用，回退逐个：{}", e.getMessage());
        }
        for (Long id : list) {
            UserVO vo = user(id);
            if (vo != null) {
                map.put(id, vo);
            }
        }
        return map;
    }

    private BlockRelationVO relation(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return null;
        }
        try {
            Result<BlockRelationVO> r = userClient.blocked(userId, targetId);
            if (ok(r)) {
                return r.getData();
            }
        } catch (Exception e) {
            log.debug("[chat][feign][block] 拉黑关系不可用（{}->{}）：{}", userId, targetId, e.getMessage());
        }
        return null;
    }

    private boolean blockedEither(Long userA, Long userB) {
        BlockRelationVO r = relation(userA, userB);
        return r != null && (Boolean.TRUE.equals(r.getBlockedByMe()) || Boolean.TRUE.equals(r.getBlockedMe()));
    }

    private BlockSets blockSets(Long userId, Collection<Long> peerIds) {
        Set<Long> mine = new HashSet<>();
        Set<Long> theirs = new HashSet<>();
        for (Long peerId : peerIds) {
            if (peerId == null || Objects.equals(peerId, userId)) {
                continue;
            }
            BlockRelationVO r = relation(userId, peerId);
            if (r == null) {
                continue;
            }
            if (Boolean.TRUE.equals(r.getBlockedByMe())) {
                mine.add(peerId);
            }
            if (Boolean.TRUE.equals(r.getBlockedMe())) {
                theirs.add(peerId);
            }
        }
        return new BlockSets(mine, theirs);
    }

    /** 一轮会话列表的拉黑关系快照（避免每个人重复查两次） */
    private record BlockSets(Set<Long> myBlocks, Set<Long> myBlockers) {
    }
}
