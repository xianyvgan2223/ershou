package com.ershou.cloud.chat.controller;

import com.ershou.cloud.chat.dto.BatchDeleteSessionsDTO;
import com.ershou.cloud.chat.dto.OpenSessionDTO;
import com.ershou.cloud.chat.dto.SendMessageDTO;
import com.ershou.cloud.chat.service.ChatService;
import com.ershou.cloud.chat.vo.BatchAuditResultVO;
import com.ershou.cloud.chat.vo.ChatMessageVO;
import com.ershou.cloud.chat.vo.ChatSessionVO;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 私聊 REST 接口（路径与 JSON 与旧单体完全一致，Web/App 零改动）。
 *
 * <p>鉴权：网关 8088 统一校验 Sa-Token 后注入 {@code X-User-Id}，
 * common 的 UserContextInterceptor 写入 {@link UserContext}；本服务不自行解析 token
 * （唯一例外是 WS 握手，由 ChatHandshakeInterceptor 校验）。
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> sessions(
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        return Result.ok(chatService.listSessions(UserContext.requireUserId(), includeDeleted));
    }

    @PostMapping("/sessions")
    public Result<ChatSessionVO> open(@Valid @RequestBody OpenSessionDTO dto) {
        return Result.ok(chatService.openSession(UserContext.requireUserId(), dto.getItemId()));
    }

    @GetMapping("/sessions/{id}")
    public Result<ChatSessionVO> detail(@PathVariable Long id) {
        return Result.ok(chatService.sessionDetail(UserContext.requireUserId(), id));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        chatService.deleteSession(UserContext.requireUserId(), id);
        return Result.ok();
    }

    @PostMapping("/sessions/batch-delete")
    public Result<BatchAuditResultVO> batchDelete(@Valid @RequestBody BatchDeleteSessionsDTO dto) {
        return Result.ok(chatService.batchDelete(UserContext.requireUserId(), dto.getIds()));
    }

    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessageVO>> messages(@PathVariable Long id,
                                                @RequestParam(required = false) Long beforeId,
                                                @RequestParam(required = false) Long afterId,
                                                @RequestParam(required = false) Integer size) {
        return Result.ok(chatService.listMessages(UserContext.requireUserId(), id, beforeId, afterId, size));
    }

    @PostMapping("/messages")
    public Result<ChatMessageVO> send(@Valid @RequestBody SendMessageDTO dto) {
        return Result.ok(chatService.send(UserContext.requireUserId(), dto.getSessionId(),
                dto.getMsgType(), dto.getContent(), dto.getClientMsgId()));
    }

    @PostMapping("/messages/{id}/recall")
    public Result<ChatMessageVO> recall(@PathVariable Long id) {
        return Result.ok(chatService.recall(UserContext.requireUserId(), id));
    }

    @PostMapping("/sessions/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        chatService.markRead(UserContext.requireUserId(), id);
        return Result.ok();
    }

    @GetMapping("/unread")
    public Result<Map<String, Object>> unread() {
        return Result.ok(chatService.unread(UserContext.requireUserId()));
    }

    @GetMapping("/quick-replies")
    public Result<List<String>> quickReplies() {
        return Result.ok(chatService.quickReplies());
    }

    /** 私聊通道状态（在线连接数，排查用） */
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(chatService.status(UserContext.requireUserId()));
    }
}
