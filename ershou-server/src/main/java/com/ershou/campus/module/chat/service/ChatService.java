package com.ershou.campus.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.ContentCipher;
import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.admin.vo.BatchAuditResultVO;
import com.ershou.campus.module.ai.AuditDecision;
import com.ershou.campus.module.ai.AuditResult;
import com.ershou.campus.module.ai.ContentAuditService;
import com.ershou.campus.module.chat.config.ChatProperties;
import com.ershou.campus.module.chat.entity.ChatMessage;
import com.ershou.campus.module.chat.entity.ChatSession;
import com.ershou.campus.module.chat.mapper.ChatMessageMapper;
import com.ershou.campus.module.chat.mapper.ChatSessionMapper;
import com.ershou.campus.module.chat.vo.ChatMessageVO;
import com.ershou.campus.module.chat.vo.ChatSessionVO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.mapper.ItemMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserBlockService;
import com.ershou.campus.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 私聊：会话 + 消息 + 风控 + 未读（PRD 5.5 / 开发文档 7.6）。
 *
 * <p>几条边界：
 * <ul>
 *   <li>未读数以 DB 为权威（chat_session.unread_a/unread_b 条件自增），
 *       Redis Hash 只是顶栏红点的快速读取路径，读会话列表时用 DB 值回写自愈；</li>
 *   <li>文本正文 AES 加密落库（schema 约定），图片/卡片类存原文；</li>
 *   <li>高危消息"留痕 + 拦截"：消息入库但 blocked=1，只有发送方能在自己的会话里看到，
 *       接收方永远读不到（FR-IM-04）；</li>
 *   <li>可疑词不篡改原文，随消息返回 riskWarning 由前端弱提醒（FR-IM-05）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final int MSG_TEXT = 1;
    public static final int MSG_IMAGE = 2;

    private static final int RISK_NONE = 0;
    private static final int RISK_REVIEW = 2;
    private static final int RISK_BLOCK = 3;
    private static final String PLACEHOLDER = "[消息内容不可用]";

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ItemMapper itemMapper;
    private final UserService userService;
    private final ContentAuditService auditService;
    private final ChatPushService pushService;
    private final ChatRiskRecorder riskRecorder;
    private final UserBlockService blockService;
    private final ContentCipher cipher;
    private final StringRedisTemplate redis;
    private final ChatProperties props;

    // ------------------------------------------------------------ 会话

    /** 从商品详情发起点对点会话：同一对人 + 同一件商品只有一条会话（唯一索引兜底并发） */
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO openSession(Long userId, Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        Long sellerId = item.getSellerId();
        if (Objects.equals(sellerId, userId)) {
            throw new BizException(ResultCode.CHAT_SELF);
        }
        if (blockService.blockedEither(userId, sellerId)) {
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
            // 之前删过这个会话，现在又主动从商品页点进来 —— 视为"重新开启"，撤销删除。
            // 不撤销的话会出现最困惑的一种情况：点了私聊、消息也发出去了，
            // 但回列表一看会话不见了（因为还带着删除标记）。
            sessionMapper.restoreDeleted(session.getId());
            log.info("[chat][open][{}][{}] 重新开启已删除的会话", userId, session.getId());
            session = sessionMapper.selectById(session.getId());
        }
        return toSessionVO(session, userId, Map.of(itemId, item), peerMap(sellerId),
                blockService.blockedUserIds(userId), blockService.blockerIds(userId));
    }

    /**
     * 删除聊天记录（会话）。
     *
     * <p><b>每用户软删除</b>：一条会话是两个人共用的，直接删整行会把对方的会话、
     * 对方的历史消息入口一起删掉 —— 那是别人的数据，不该被我的一次点击带走。
     * 所以只标记"我这一侧删了"：
     * <ul>
     *   <li>我的列表与未读汇总里不再出现它（红点一起归零，否则会出现"列表没有、
     *       角标还在"的消不掉红点）；</li>
     *   <li>对方的会话列表不受任何影响；</li>
     *   <li>对方再发消息时自动恢复（见 {@link #send}）—— 与微信一致：删了聊天，
     *       对方再发消息，会话重新出现。</li>
     * </ul>
     *
     * <p>消息记录本身不删（对方还要看），所以这里没有物理删除任何一行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(Long userId, Long sessionId) {
        ChatSession session = requireMember(sessionId, userId);
        boolean sinceA = Objects.equals(session.getUserA(), userId);
        if (session.deletedFor(userId)) {
            // 幂等：重复点删除不报错（前端连点、或两端同时删）
            log.debug("[chat][delete][{}][{}] 已是删除状态，忽略", userId, sessionId);
            return;
        }
        sessionMapper.markDeleted(sessionId, sinceA);
        // 未读快照一起清：DB 已经归零，Redis 里留着就会算进顶栏红点
        try {
            redis.opsForHash().delete(RedisKeys.chatUnread(userId), String.valueOf(sessionId));
        } catch (Exception e) {
            log.warn("[chat][delete][{}] 清理 Redis 未读快照失败：{}", userId, e.getMessage());
        }
        log.info("[chat][delete][{}][{}] 已从本人列表删除会话（对方不受影响）", userId, sessionId);
    }

    /**
     * 批量删除聊天记录（会话列表的"多选删除"）。
     *
     * <p>逐条独立处理，**不因单条失败整批回滚**（与审核工作台的批量处置同口径）：
     * 勾了 20 条、其中 1 条已被对方删了或本来就不属于我，不该让另外 19 条白干。
     * 返回成功/失败数与每条失败的原因，前端据此提示"这几条没删掉"。
     *
     * <p>为什么不做成"循环调单个接口"：20 条就是 20 次往返，手机上体感明显；
     * 而且中途失败会留下"删了一半"的脏状态，前端也没法告诉用户哪几条没成功。
     */
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
                // 不是我的会话（70002）/ 不存在：如实回报，并在该行里说明原因
                failures.add(new BatchAuditResultVO.Failure(sessionId, e.getMessage()));
            } catch (Exception e) {
                log.warn("[chat][batch-delete][{}][{}] 删除失败：{}", userId, sessionId, e.getMessage());
                failures.add(new BatchAuditResultVO.Failure(sessionId, "删除失败：" + e.getMessage()));
            }
        }
        log.info("[chat][batch-delete][{}] success={} failed={}", userId, success, failures.size());
        return new BatchAuditResultVO(success, failures.size(), failures);
    }

    /**
     * 我的会话列表：按最后消息时间倒序，带对方信息、商品卡片、未读数。
     *
     * <p>{@code includeDeleted=false}（默认）时**剔除我删过的会话** —— 删除聊天记录
     * 的意义就是"它从我的列表里消失"。但消息本身与对方的会话都还在，
     * 对方再发消息时这条会自己回来。
     */
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
        Map<Long, SysUser> peers = userService.mapByIds(peerIds);
        Map<Long, Item> items = itemIds.isEmpty() ? Map.of()
                : itemMapper.selectBatchIds(itemIds).stream()
                        .collect(Collectors.toMap(Item::getId, i -> i, (x, y) -> x));
        // 用 DB（权威）刷新 Redis 未读快照：Redis 丢数据或被改脏都能自愈
        syncUnreadCache(userId, sessions);
        // 拉黑关系一次查全（我拉黑的 + 拉黑我的），会话列表里标注能否继续发消息
        Set<Long> myBlocks = blockService.blockedUserIds(userId);
        Set<Long> myBlockers = blockService.blockerIds(userId);
        return sessions.stream().map(s -> toSessionVO(s, userId, items, peers, myBlocks, myBlockers)).toList();
    }

    public ChatSessionVO sessionDetail(Long userId, Long sessionId) {
        ChatSession session = requireMember(sessionId, userId);
        Map<Long, Item> items = session.getItemId() == null ? Map.of()
                : Optional.ofNullable(itemMapper.selectById(session.getItemId()))
                        .map(i -> Map.of(i.getId(), i)).orElse(Map.of());
        return toSessionVO(session, userId, items, peerMap(session.peerOf(userId)),
                blockService.blockedUserIds(userId), blockService.blockerIds(userId));
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
            redis.opsForHash().delete(RedisKeys.chatUnread(userId), String.valueOf(sessionId));
        } catch (Exception e) {
            log.warn("[chat][read][{}] 清理 Redis 未读失败：{}", userId, e.getMessage());
        }
        Long peerId = session.peerOf(userId);
        if (peerId != null) {
            pushService.push(peerId, "read", Map.of("sessionId", sessionId, "readerId", userId));
        }
    }

    /** 未读汇总：顶栏红点读 Redis 快照，快照不存在时回源 DB 并回填 */
    public Map<String, Object> unread(Long userId) {
        Map<String, Integer> bySession = new LinkedHashMap<>();
        // 无论缓存有没有命中，都要查一次"我的会话"——DB 是权威，
        // 而且要用它剔除缓存里的**僵尸会话**（见下）。
        // 同时剔除"我删过的会话"：删掉的会话不该再贡献红点，否则列表里看不到它、
        // 角标却一直挂着数字，用户只会觉得红点消不掉。
        List<ChatSession> sessions = sessionMapper.selectList(Wrappers.<ChatSession>lambdaQuery()
                        .and(w -> w.eq(ChatSession::getUserA, userId).or().eq(ChatSession::getUserB, userId)))
                .stream().filter(s -> !s.deletedFor(userId)).toList();
        Map<Long, ChatSession> byId = sessions.stream()
                .collect(Collectors.toMap(ChatSession::getId, s -> s, (a, b) -> a));

        Map<Object, Object> cached = Map.of();
        try {
            cached = redis.opsForHash().entries(RedisKeys.chatUnread(userId));
        } catch (Exception e) {
            log.warn("[chat][unread][{}] 读取 Redis 快照失败，回源 DB：{}", userId, e.getMessage());
        }

        // ⚠️ 这里踩过一个真 bug：原来是"缓存非空就直接用缓存"，
        // 于是**被删掉的会话在 Hash 里留下僵尸字段**，未读数只增不减
        // （实测出现"红点一直显示 11、但库里未读其实是 0"）。
        // 现在以 DB 的会话集合为准：只采纳仍然存在的会话；顺带把过期字段清掉、用 DB 值校准。
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
                    stale.add(k); // 会话已被删除：清掉这个僵尸字段，不要计入未读
                    return;
                }
                bySession.put(String.valueOf(sid), s.unreadOf(userId));
            });
            if (!stale.isEmpty()) {
                try {
                    redis.opsForHash().delete(RedisKeys.chatUnread(userId), stale.toArray());
                    log.info("[chat][unread][{}] 清掉 {} 个已删除会话的僵尸未读字段", userId, stale.size());
                } catch (Exception e) {
                    log.warn("[chat][unread][{}] 清理僵尸未读字段失败：{}", userId, e.getMessage());
                }
            }
            // 缓存里没见过的会话（新建但还没同步）补上
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

    /**
     * 历史消息：id 游标，返回时间正序。
     *
     * <p>beforeId = 往前翻历史（越翻越旧）；afterId = 断线补拉（只取比它新的，
     * 取最新 limit 条，一次就能追上最新进度）。两者互斥。
     */
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

    /** 发消息：限额 → 机审 → 落库 → 会话摘要/未读 → 推送（落库成功才返回，前端据此收 ACK） */
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageVO send(Long senderId, Long sessionId, Integer msgType, String rawContent, String clientMsgId) {
        ChatSession session = requireMember(sessionId, senderId);
        Long receiverId = session.peerOf(senderId);
        if (blockService.blockedEither(senderId, receiverId)) {
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

        AuditResult audit = props.isAuditEnabled()
                ? auditService.auditText(content, "chat")
                : AuditResult.pass();
        if (audit.decision() == AuditDecision.BLOCK) {
            // 留痕走独立事务：下面这行抛出后本事务回滚，但证据必须留在库里
            riskRecorder.recordBlocked(session, senderId, receiverId, type, content, audit.reason());
            throw new BizException(ResultCode.RISK_BLOCKED, audit.reason());
        }
        int riskLevel = audit.decision() == AuditDecision.REVIEW ? RISK_REVIEW : RISK_NONE;

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
        // 对方删过这个会话的话，把它恢复出来 —— 否则消息落库了、对方却永远看不到，
        // 等于静默丢消息（这是"每用户软删除"最容易漏的一步）
        if (session.deletedFor(receiverId)) {
            sessionMapper.restoreDeleted(sessionId);
            log.info("[chat][send][{}] 接收方此前删除了会话，已自动恢复", sessionId);
        }
        incrementUnread(session, receiverId);

        ChatMessageVO vo = toMessageVO(message, senderId, clientMsgId);
        if (riskLevel >= RISK_REVIEW) {
            vo.setRiskWarning(audit.reason());
        }
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

        // 撤回的正好是最后一条 → 会话列表预览同步改掉，否则列表还显示已撤回的内容
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
     * 新号限额：注册 {@code newUserDays} 天内每日私聊不超过 {@code sys_user.chat_limit} 条。
     *
     * <p>**默认关闭**（{@code ershou.chat.daily-limit-enabled=false}）。
     * 2026-10-01 产品决定取消：校园场景下"新号每天 20 条"很容易误伤 ——
     * 正常砍价来回几句就见底，用户只会当成"聊天坏了"。
     * 代码与配置都留着，需要时打开开关即可恢复。
     */
    private void checkDailyLimit(Long userId) {
        if (!props.isDailyLimitEnabled()) {
            return;
        }
        SysUser user = userService.getById(userId);
        boolean newUser = user.getCreatedAt() != null
                && user.getCreatedAt().isAfter(LocalDateTime.now().minusDays(props.getNewUserDays()));
        if (!newUser) {
            return;
        }
        int limit = user.getChatLimit() == null ? 20 : user.getChatLimit();
        try {
            String key = RedisKeys.chatLimit(userId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 到期时间为当天 23:59:59（开发文档 4.9 限额规约）
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
            String key = RedisKeys.chatUnread(receiverId);
            redis.opsForHash().increment(key, String.valueOf(session.getId()), 1L);
            redis.expire(key, Duration.ofDays(7));
        } catch (Exception e) {
            log.warn("[chat][unread][{}] Redis 快照自增失败（DB 已记）：{}", receiverId, e.getMessage());
        }
    }

    private void syncUnreadCache(Long userId, List<ChatSession> sessions) {
        try {
            String key = RedisKeys.chatUnread(userId);
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

    private Map<Long, SysUser> peerMap(Long peerId) {
        return peerId == null ? Map.of() : userService.mapByIds(List.of(peerId));
    }

    private ChatSessionVO toSessionVO(ChatSession session, Long userId, Map<Long, Item> items,
                                      Map<Long, SysUser> peers, Set<Long> myBlocks, Set<Long> myBlockers) {
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
        SysUser peer = peerId == null ? null : peers.get(peerId);
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
        Item item = session.getItemId() == null ? null : items.get(session.getItemId());
        if (item != null) {
            vo.setItemId(item.getId());
            vo.setItemTitle(item.getTitle());
            vo.setItemCover(item.getCoverUrl());
            vo.setItemPrice(item.getPrice());
            vo.setItemStatus(item.getStatus());
            vo.setItemStatusText(ItemService.statusText(item.getStatus()));
            // 角色是相对"来源商品"而言的：谁是卖家一目了然（买卖双方不分的根因）
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
            return Boolean.TRUE.equals(redis.hasKey(RedisKeys.chatOnline(userId)));
        } catch (Exception e) {
            return false;
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
