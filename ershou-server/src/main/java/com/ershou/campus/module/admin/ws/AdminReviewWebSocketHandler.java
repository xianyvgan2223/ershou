package com.ershou.campus.module.admin.ws;

import com.ershou.campus.module.admin.service.ReviewPushService;
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

/**
 * 上架审核实时推送（原生 WebSocket，JSON 文本帧）：{@code ws://host/ws/admin/review?token=xxx}
 *
 * <p>推送事件（服务端 → 客户端）：
 * <pre>
 * {"type":"ready",   "data":{"userId":3,"onlineReviewers":2}}
 * {"type":"queued",  "data":{"pending":12,"item":{…ItemReviewVO…}}}   // 有新商品上架 / 被改名，item 为 null 表示只是条数变了
 * {"type":"handled", "data":{"pending":11,"itemId":88}}              // 某条被别人处置了，本地把它移出列表
 * {"type":"pong",    "data":{"ts":…}}
 * </pre>
 * 客户端 → 服务端只有 {@code {"type":"ping"}}（同时刷新"在线审核员"名单）。
 *
 * <p>为什么推"条数 + 单条"而不是"整个列表"：审核页可能有几十上百条，
 * 每次新商品上架都重推全量既浪费又会让正在看详情的审核员丢失展开态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminReviewWebSocketHandler extends TextWebSocketHandler {

    private final ReviewPushService pushService;
    private final ObjectMapper json;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        pushService.register(userId, nickname(session), session);
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("userId", userId);
        ready.put("instanceId", pushService.instanceId());
        ready.put("onlineReviewers", pushService.onlineReviewerCount());
        pushService.sendTo(session, "ready", ready);
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
                case "ping" -> {
                    pushService.touch(userId, nickname(session));
                    pushService.sendTo(session, "pong", Map.of("ts", System.currentTimeMillis()));
                }
                default -> pushService.sendTo(session, "error",
                        Map.of("message", "不支持的消息类型：" + type));
            }
        } catch (Exception e) {
            log.debug("[admin][review][ws][{}] 消息处理失败：{}", userId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = userId(session);
        if (userId != null) {
            pushService.unregister(userId, session);
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

    private String nickname(WebSocketSession session) {
        Object value = session.getAttributes().get(AdminReviewHandshakeInterceptor.ATTR_NICKNAME);
        return value instanceof String name ? name : null;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // 关闭失败无所谓，容器会清理
        }
    }
}
