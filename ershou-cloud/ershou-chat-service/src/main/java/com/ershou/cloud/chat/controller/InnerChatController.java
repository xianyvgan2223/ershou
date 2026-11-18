package com.ershou.cloud.chat.controller;

import com.ershou.cloud.chat.service.ChatService;
import com.ershou.cloud.chat.vo.ChatSessionVO;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内部接口（full-split-contract 第三节 chat-service 契约，**不经过网关**）。
 *
 * <ul>
 *   <li>{@code GET /inner/chat/unread?userId=} → 未读数（供用户页/管理端）；</li>
 *   <li>{@code GET /inner/chat/sessions?userId=&page=&pageSize=} → 会话列表（供管理端，分页）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/inner/chat")
@RequiredArgsConstructor
public class InnerChatController {

    private final ChatService chatService;

    @GetMapping("/unread")
    public Result<Map<String, Object>> unread(@RequestParam("userId") Long userId) {
        return Result.ok(chatService.unread(userId));
    }

    @GetMapping("/sessions")
    public Result<PageResult<ChatSessionVO>> sessions(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "pageSize", defaultValue = "10") long pageSize) {
        return Result.ok(chatService.pageSessions(userId, page, pageSize));
    }
}
