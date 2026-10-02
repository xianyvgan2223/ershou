package com.ershou.campus.module.admin.service;

import com.ershou.campus.common.util.RedisKeys;
import com.ershou.campus.module.admin.config.ReviewProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上架审核的实时推送：本机 session 直推 + Redis Pub/Sub 跨实例广播。
 *
 * <p>与 {@link com.ershou.campus.module.chat.service.ChatPushService} 同一套机制（开发文档 ADR-06），
 * 差别在于<b>接收方是"所有审核员"而不是某一个用户</b>：新商品上架要让每个开着审核页的人都看到，
 * 否则两个审核员看到的队列不一样，"保证每条都被看过"就无从谈起。
 * 所以这里的广播是"频道级"的，不做 userId 过滤（回声过滤仍靠 instanceId）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewPushService {

    /** 单实例内存里的审核员连接：userId → 会话（同一账号多标签页都算） */
    private final Map<Long, Set<WebSocketSession>> localSessions = new ConcurrentHashMap<>();
    /** 各连接的最后活跃时间，用于 TTL 之外的本机兜底清理 */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ReviewProperties props;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public String instanceId() {
        return instanceId;
    }

    public boolean realtimeEnabled() {
        return props.isRealtimeEnabled();
    }

    public void register(Long userId, String nickname, WebSocketSession session) {
        localSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        touch(userId, nickname);
        log.info("[admin][review][ws][{}] 审核页已连接，本机审核员会话数={}", userId, localSessionCount());
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = localSessions.get(userId);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            localSessions.remove(userId);
            try {
                redis.opsForHash().delete(RedisKeys.adminReviewOnline(), String.valueOf(userId));
            } catch (Exception ignored) {
                // Redis 不可用不影响连接清理
            }
        }
    }

    /** 心跳：既刷新连接活跃时间，也刷新 Redis 里的在线名单（顶部"几位审核员在线"读它） */
    public void touch(Long userId, String nickname) {
        lastSeen.put(key(userId), System.currentTimeMillis());
        try {
            redis.opsForHash().put(RedisKeys.adminReviewOnline(), String.valueOf(userId),
                    StringUtils.hasText(nickname) ? nickname : String.valueOf(userId));
            redis.expire(RedisKeys.adminReviewOnline(), props.getOnlineTtl());
        } catch (Exception e) {
            log.debug("[admin][review][online] 心跳写入失败：{}", e.getMessage());
        }
    }

    /** 广播给所有审核员（新上架 / 被处置） */
    public void broadcast(String type, Object data) {
        pushLocal(type, data);
        publish(type, data);
    }

    /** 只推本机（Redis 广播回来时走这里，避免回声） */
    public void pushLocal(String type, Object data) {
        if (localSessions.isEmpty()) {
            return;
        }
        String payload = buildPayload(type, data);
        for (Map.Entry<Long, Set<WebSocketSession>> entry : localSessions.entrySet()) {
            for (WebSocketSession session : entry.getValue()) {
                sendQuietly(session, payload);
            }
        }
    }

    /** 单条连接直发（握手 ready / pong 走这条，不广播） */
    public void sendTo(WebSocketSession session, String type, Object data) {
        sendQuietly(session, buildPayload(type, data));
    }

    private void publish(String type, Object data) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("instanceId", instanceId);
            envelope.put("type", type);
            envelope.put("data", data);
            redis.convertAndSend(RedisKeys.adminReviewChannel(), json.writeValueAsString(envelope));
        } catch (Exception e) {
            // 广播失败只影响"审核员在别的实例上"的实时性，本机推送与落库都已完成
            log.warn("[admin][review][push] 跨实例广播失败：{}", e.getMessage());
        }
    }

    /** Redis 订阅回调（AdminReviewRedisConfig 调用） */
    public void onRemoteMessage(String body) {
        try {
            var node = json.readTree(body);
            if (instanceId.equals(node.path("instanceId").asText(""))) {
                return;
            }
            pushLocal(node.path("type").asText("queued"), json.convertValue(node.path("data"), Object.class));
        } catch (Exception e) {
            log.warn("[admin][review][push] 跨实例消息解析失败：{}", e.getMessage());
        }
    }

    public int localSessionCount() {
        return localSessions.values().stream().mapToInt(Set::size).sum();
    }

    public int localReviewerCount() {
        return localSessions.size();
    }

    /** 在线审核员数：本机连接 + Redis 名单（多实例时取并集的上界，宁可多报也别漏报） */
    public int onlineReviewerCount() {
        int local = localReviewerCount();
        try {
            Long size = redis.opsForHash().size(RedisKeys.adminReviewOnline());
            return Math.max(local, size == null ? 0 : size.intValue());
        } catch (Exception e) {
            return local;
        }
    }

    public Map<Long, String> onlineReviewers() {
        Map<Long, String> result = new LinkedHashMap<>();
        for (Long userId : localSessions.keySet()) {
            result.put(userId, String.valueOf(userId));
        }
        try {
            Map<Object, Object> remote = redis.opsForHash().entries(RedisKeys.adminReviewOnline());
            for (Map.Entry<Object, Object> e : remote.entrySet()) {
                try {
                    result.putIfAbsent(Long.valueOf(String.valueOf(e.getKey())), String.valueOf(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过
                }
            }
        } catch (Exception ignored) {
            // Redis 不可用时只返回本机
        }
        return result;
    }

    /**
     * 兜底清理：个别容器不触发 afterConnectionClosed 时，靠这里把僵尸连接摘掉。
     *
     * <p>注意<b>不要</b>把 {@code lastSeen} 超时当成断开：连接 OPEN 且客户端 15 秒一次 ping，
     * 这里只负责摘掉真正 closed 的 session（以及顺带清 lastSeen 里的孤儿 key）。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void pruneStale() {
        for (Map.Entry<Long, Set<WebSocketSession>> entry : localSessions.entrySet()) {
            Set<WebSocketSession> set = entry.getValue();
            set.removeIf(session -> !session.isOpen());
            if (set.isEmpty()) {
                localSessions.remove(entry.getKey());
                lastSeen.remove(key(entry.getKey()));
            }
        }
        lastSeen.keySet().removeIf(k -> !localSessions.containsKey(Long.valueOf(k)));
    }

    private static String key(Long userId) {
        return String.valueOf(userId);
    }

    private String buildPayload(String type, Object data) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("data", data);
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"data\":{\"message\":\"序列化失败\"}}";
        }
    }

    private void sendQuietly(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            // WebSocketSession 不是线程安全的：并发发消息要串行化
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            log.warn("[admin][review][ws] 推送失败（连接可能已断开）：{}", e.getMessage());
        }
    }

    /** Redis 订阅到的原始字节解码（配置类里用得到） */
    public static String decode(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
