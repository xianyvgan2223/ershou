package com.ershou.cloud.admin.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 上架审核实时推送（原生 WebSocket，JSON 文本帧）：{@code ws://host/ws/admin/review?token=xxx}。
 *
 * <p><b>T107 首版范围</b>：只迁移连接协议本身 —— 握手鉴权、{@code ready}、心跳 {@code ping}/{@code pong}、
 * 在线审核员计数，保证前端审核页不报错、能显示"实时接收中"。
 * 真正的 {@code queued}/{@code handled} 广播事件需要 item-service 在入队/处置时发布（旧单体由
 * ItemReviewService 直接 broadcast）；这需要 item-service 侧配合（MQ 或 Redis Pub/Sub），
 * 列为交付报告中的遗留项，不在本服务单方面改造范围内。
 *
 * <p>帧协议与旧单体 AdminReviewWebSocketHandler 保持一致：{@code ready/pong/error}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminReviewWebSocketHandler extends TextWebSocketHandler {

    private final ReviewOnlineRegistry onlineRegistry;
    private final ObjectMapper json;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        onlineRegistry.increment();
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("userId", userId);
        ready.put("instanceId", instanceId);
        ready.put("onlineReviewers", onlineRegistry.count());
        send(session, "ready", ready);
        log.info("[admin][review][ws][{}] 审核页已连接，本机在线审核员={}", userId, onlineRegistry.count());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = userId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        try {
            JsonNode node = json.readTree(message.getPayload());
            String type = node.path("type").asText("");
            switch (type) {
                case "ping" -> send(session, "pong", Map.of("ts", System.currentTimeMillis()));
                default -> send(session, "error", Map.of("message", "不支持的消息类型：" + type));
            }
        } catch (Exception e) {
            log.debug("[admin][review][ws][{}] 消息处理失败：{}", userId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (userId(session) != null) {
            onlineRegistry.decrement();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[admin][review][ws] 传输异常：{}", exception.getMessage());
        closeQuietly(session);
    }

    private Long userId(WebSocketSession session) {
        Object value = session.getAttributes().get(AdminReviewHandshakeInterceptor.ATTR_USER_ID);
        return value instanceof Long id ? id : null;
    }

    private void send(WebSocketSession session, String type, Object data) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("data", data);
            String text = json.writeValueAsString(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (Exception e) {
            log.warn("[admin][review][ws] 推送失败（连接可能已断开）：{}", e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // 容器会清理
        }
    }

    /** 供未来广播用：当前只记录实例 id（与旧单体 ReviewPushService.instanceId 语义一致） */
    public String instanceId() {
        return instanceId;
    }
}
