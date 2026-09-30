package com.ershou.campus.module.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.chat.dto.OpenSessionDTO;
import com.ershou.campus.module.chat.dto.SendMessageDTO;
import com.ershou.campus.module.chat.service.ChatService;
import com.ershou.campus.module.chat.vo.ChatMessageVO;
import com.ershou.campus.module.chat.vo.ChatSessionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 私聊 REST 接口（开发文档 12 章）：实时走 /ws/chat，这里负责会话列表、
 * 历史消息与"WebSocket 不可用时"的发送兜底。
 * 全部需要登录——SaTokenConfig 未把 /api/v1/chat/** 放进白名单。
 */
@Tag(name = "私聊")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "我的会话列表（含未读数、对方在线状态、商品卡片）")
    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> sessions() {
        return Result.ok(chatService.listSessions(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "从商品发起会话（已存在则复用）")
    @PostMapping("/sessions")
    public Result<ChatSessionVO> open(@Valid @RequestBody OpenSessionDTO dto) {
        return Result.ok(chatService.openSession(StpUtil.getLoginIdAsLong(), dto.getItemId()));
    }

    @Operation(summary = "会话详情（聊天窗顶部商品卡片与对方信息）")
    @GetMapping("/sessions/{id}")
    public Result<ChatSessionVO> detail(@PathVariable Long id) {
        return Result.ok(chatService.sessionDetail(StpUtil.getLoginIdAsLong(), id));
    }

    @Operation(summary = "历史消息（id 游标：beforeId 往前翻，afterId 断线补拉，两者互斥）")
    @GetMapping("/sessions/{id}/messages")
    public Result<List<ChatMessageVO>> messages(@PathVariable Long id,
                                                @RequestParam(required = false) Long beforeId,
                                                @RequestParam(required = false) Long afterId,
                                                @RequestParam(required = false) Integer size) {
        return Result.ok(chatService.listMessages(StpUtil.getLoginIdAsLong(), id, beforeId, afterId, size));
    }

    @Operation(summary = "发送消息（WebSocket 未连通时的兜底通道）")
    @PostMapping("/messages")
    public Result<ChatMessageVO> send(@Valid @RequestBody SendMessageDTO dto) {
        return Result.ok(chatService.send(StpUtil.getLoginIdAsLong(), dto.getSessionId(),
                dto.getMsgType(), dto.getContent(), dto.getClientMsgId()));
    }

    @Operation(summary = "撤回消息（仅发送方，限时 2 分钟）")
    @PostMapping("/messages/{id}/recall")
    public Result<ChatMessageVO> recall(@PathVariable Long id) {
        return Result.ok(chatService.recall(StpUtil.getLoginIdAsLong(), id));
    }

    @Operation(summary = "标记会话已读")
    @PostMapping("/sessions/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        chatService.markRead(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "未读汇总（顶栏红点：总数 + 每个会话的未读数）")
    @GetMapping("/unread")
    public Result<Map<String, Object>> unread() {
        return Result.ok(chatService.unread(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "快捷话术")
    @GetMapping("/quick-replies")
    public Result<List<String>> quickReplies() {
        return Result.ok(chatService.quickReplies());
    }

    @Operation(summary = "私聊通道状态（在线连接数，排查用）")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(chatService.status(StpUtil.getLoginIdAsLong()));
    }
}
