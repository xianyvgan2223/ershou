package com.ershou.campus.module.chat.service;

import com.ershou.campus.common.util.ContentCipher;
import com.ershou.campus.module.chat.entity.ChatMessage;
import com.ershou.campus.module.chat.entity.ChatSession;
import com.ershou.campus.module.chat.mapper.ChatMessageMapper;
import com.ershou.campus.module.chat.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 风控留痕：高危消息要"拦截发送 + 记录在案"（PRD FR-IM-04）。
 *
 * <p>单独一个 Bean 是为了能开 REQUIRES_NEW：ChatService.send 在拦截时会抛业务异常，
 * 外层事务必然回滚——如果留痕跟在同一个事务里，记录会跟着消息一起消失，
 * 风控画像和纠纷取证就都没了。挂在新事务上，外层回滚不影响它提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRiskRecorder {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final ContentCipher cipher;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordBlocked(ChatSession session, Long senderId, Long receiverId,
                              int msgType, String content, String reason) {
        ChatMessage blocked = new ChatMessage();
        blocked.setSessionId(session.getId());
        blocked.setSenderId(senderId);
        blocked.setReceiverId(receiverId);
        blocked.setItemId(session.getItemId());
        blocked.setMsgType(msgType);
        blocked.setContent(msgType == ChatService.MSG_TEXT ? cipher.encrypt(content) : content);
        blocked.setRiskLevel(3);
        blocked.setBlocked(1);
        messageMapper.insert(blocked);

        if (session.getRiskLevel() == null || session.getRiskLevel() < 3) {
            ChatSession update = new ChatSession();
            update.setId(session.getId());
            update.setRiskLevel(3);
            sessionMapper.updateById(update);
        }
        log.warn("[chat][blocked][{}->{}] session={} reason={}", senderId, receiverId, session.getId(), reason);
    }
}
