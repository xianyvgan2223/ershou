package com.ershou.cloud.chat.service;

import com.ershou.cloud.chat.config.ChatProperties;
import com.ershou.cloud.chat.util.ChatRedisKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 私聊推送：本机 session 直推 + Redis Pub/Sub 跨实例广播（ADR-06）。
 *
 * <p>实例 id 用于跳过自己发出去的广播回声：A 实例 publish 后，订阅端在 A、B 都会收到；
 * A 已经在"直推"里发过一次，再发就重复了。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPushService {

    /** 单实例内存里的在线连接：userId → sessions（同一用户多标签页/多设备） */
    private final Map<Long, Set<WebSocketSession>> localSessions = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ChatProperties props;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    public String instanceId() {
        return instanceId;
    }

    public void register(Long userId, WebSocketSession session) {
        localSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        touch(userId);
        log.info("[chat][ws][{}] 上线，本机在线会话数={}", userId, localSessions.get(userId).size());
    }

    /**
     * 在线心跳：key 有 TTL，只要连接在（或客户端还在 ping）就算在线。
     * 断线不主动删 key——客户端可能只是刷新页面，让 TTL 自然过期更稳（也避免多标签页互相踩）。
     */
    public void touch(Long userId) {
        try {
            redis.opsForValue().set(ChatRedisKeys.chatOnline(userId), "1", props.getOnlineTtl());
        } catch (Exception e) {
            log.warn("[chat][online][{}] 心跳写入失败：{}", userId, e.getMessage());
        }
    }

    public void unregister(Long userId, WebSocketSession session) {
        Set<WebSocketSession> set = localSessions.get(userId);
        if (set == null) {
            return;
        }
        set.remove(session);
        if (set.isEmpty()) {
            localSessions.remove(userId);
        }
    }

    public int localSessionCount() {
        return localSessions.values().stream().mapToInt(Set::size).sum();
    }

    public int localSessionCount(Long userId) {
        Set<WebSocketSession> set = localSessions.get(userId);
        return set == null ? 0 : set.size();
    }

    /** 推给该用户：先本机直推，再广播给其它实例 */
    public void push(Long userId, String type, Object data) {
        pushLocal(userId, type, data);
        publish(userId, type, data);
    }

    /** 只推本机（Redis 广播回来时走这里，避免回声） */
    public void pushLocal(Long userId, String type, Object data) {
        Set<WebSocketSession> sessions = localSessions.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        String payload = buildPayload(type, data);
        for (WebSocketSession session : sessions) {
            sendQuietly(session, payload);
        }
    }

    /** 单条连接直发（握手回执、pong、ACK 走这条，不广播） */
    public void sendTo(WebSocketSession session, String type, Object data) {
        sendQuietly(session, buildPayload(type, data));
    }

    private void publish(Long userId, String type, Object data) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("instanceId", instanceId);
            envelope.put("userId", userId);
            envelope.put("type", type);
            envelope.put("data", data);
            redis.convertAndSend(ChatRedisKeys.chatChannel(), json.writeValueAsString(envelope));
        } catch (Exception e) {
            // 广播失败只影响"用户在别的实例上"的实时性，本机推送与落库都已完成
            log.warn("[chat][push] 跨实例广播失败：{}", e.getMessage());
        }
    }

    /** Redis 订阅回调（ChatRedisConfig 调用） */
    public void onRemoteMessage(String body) {
        try {
            var node = json.readTree(body);
            String from = node.path("instanceId").asText("");
            if (instanceId.equals(from)) {
                return;
            }
            String userId = node.path("userId").asText(null);
            if (!StringUtils.hasText(userId)) {
                return;
            }
            String type = node.path("type").asText("message");
            var data = json.convertValue(node.path("data"), Object.class);
            pushLocal(Long.valueOf(userId), type, data);
        } catch (Exception e) {
            log.warn("[chat][push] 跨实例消息解析失败：{}", e.getMessage());
        }
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
            log.warn("[chat][ws] 推送失败（连接可能已断开）：{}", e.getMessage());
        }
    }

    /* Redis 订阅到的原始字节解码 */
    public static String decode(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
