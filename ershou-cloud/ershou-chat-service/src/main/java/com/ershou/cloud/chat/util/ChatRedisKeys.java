package com.ershou.cloud.chat.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 私聊相关 Redis key（与旧单体 RedisKeys 的 chat 部分完全同名，便于数据/教程对照）。
 *
 * <p>落库位置：chat-service 的业务 Redis 走 **db4**（未读快照 / 在线心跳 / 跨实例广播 / 新号限额），
 * Sa-Token 会话固定 db0（由 SaTokenRedisConfig 单独连接）。
 */
public final class ChatRedisKeys {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private ChatRedisKeys() {
    }

    /** 新号每日私聊限额计数（ershou:limit:chat:{userId}:{yyyyMMdd}） */
    public static String chatLimit(Long userId) {
        return "ershou:limit:chat:" + userId + ":" + LocalDate.now().format(DAY);
    }

    /** 未读快照 Hash：field=sessionId, value=unread */
    public static String chatUnread(Long userId) {
        return "ershou:chat:unread:" + userId;
    }

    /** 在线心跳 key（TTL 由 ershou.chat.online-ttl 决定） */
    public static String chatOnline(Long userId) {
        return "ershou:chat:online:" + userId;
    }

    /** 跨实例推送广播频道 */
    public static String chatChannel() {
        return "ershou:chat:channel:msg";
    }
}
