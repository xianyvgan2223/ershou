package com.ershou.campus.module.chat.ws;

import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.chat.dto.SendMessageDTO;
import com.ershou.campus.module.chat.service.ChatPushService;
import com.ershou.campus.module.chat.service.ChatService;
import com.ershou.campus.module.chat.vo.ChatMessageVO;
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
 * 私聊 WebSocket（原生协议，JSON 文本帧）——开发文档 7.6 的落地：
 * ① 新号限额校验 ② 文本机审 ③ 落库 ④ 更新会话与未读 ⑤ 落库成功才回 ACK。
 *
 * <p>协议（客户端 → 服务端）：
 * <pre>
 * {"type":"send","sessionId":1,"msgType":1,"content":"hi","clientMsgId":"c-1"}
 * {"type":"read","sessionId":1}
 * {"type":"ping"}
 * </pre>
 * 服务端 → 客户端：{@code ready / ack / message / read / error / pong}。
 * 选原生 WebSocket 而不是 STOMP：前端零依赖（浏览器 WebSocket API 直接用），
 * 多实例转发由业务层 Redis Pub/Sub 负责，不需要 broker 语义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_USER_ID = "chatUserId";

    private final ChatService chatService;
    private final ChatPushService pushService;
    private final ObjectMapper json;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        pushService.register(userId, session);
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("userId", userId);
        ready.put("online", true);
        ready.put("instanceId", pushService.instanceId());
        pushService.sendTo(session, "ready", ready);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = userId(session);
        if (userId == null) {
            closeQuietly(session);
            return;
        }
        String clientMsgId = null;
        try {
            JsonNode node = json.readTree(message.getPayload());
            String type = node.path("type").asText("");
            clientMsgId = node.path("clientMsgId").asText(null);
            switch (type) {
                case "ping" -> {
                    // 心跳同时刷新在线状态：客户端每 30 秒 ping 一次，TTL 90 秒
                    pushService.touch(userId);
                    pushService.sendTo(session, "pong", Map.of("ts", System.currentTimeMillis()));
                }
                case "read" -> {
                    chatService.markRead(userId, node.path("sessionId").asLong());
                    pushService.sendTo(session, "read-ack", Map.of("sessionId", node.path("sessionId").asLong()));
                }
                case "send" -> handleSend(session, userId, node, clientMsgId);
                default -> pushService.sendTo(session, "error", errorPayload(null,
                        ResultCode.PARAM_ERROR.getCode(), "不支持的消息类型：" + type));
            }
        } catch (BizException e) {
            // 业务拒绝（限额 / 风控拦截 / 无权）：回失败帧，前端把"发送中"标成失败并提示
            pushService.sendTo(session, "error", errorPayload(clientMsgId, e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.warn("[chat][ws][{}] 消息处理失败：{}", userId, e.getMessage());
            pushService.sendTo(session, "error", errorPayload(clientMsgId,
                    ResultCode.CHAT_SEND_FAILED.getCode(), "消息发送失败，请重试"));
        }
    }

    private void handleSend(WebSocketSession session, Long userId, JsonNode node, String clientMsgId) {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setSessionId(node.path("sessionId").asLong());
        dto.setMsgType(node.path("msgType").isMissingNode() ? 1 : node.path("msgType").asInt(1));
        dto.setContent(node.path("content").asText(""));
        dto.setClientMsgId(clientMsgId);
        ChatMessageVO vo = chatService.send(userId, dto.getSessionId(), dto.getMsgType(), dto.getContent(), clientMsgId);
        pushService.sendTo(session, "ack", vo);
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
        log.warn("[chat][ws] 传输异常：{}", exception.getMessage());
        closeQuietly(session);
    }

    private Long userId(WebSocketSession session) {
        Object value = session.getAttributes().get(ATTR_USER_ID);
        return value instanceof Long id ? id : null;
    }

    private Map<String, Object> errorPayload(String clientMsgId, int code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("clientMsgId", clientMsgId);
        payload.put("code", code);
        payload.put("message", message);
        return payload;
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close(CloseStatus.NOT_ACCEPTABLE);
        } catch (Exception ignored) {
            // 关闭失败无所谓，容器会清理
        }
    }
}
