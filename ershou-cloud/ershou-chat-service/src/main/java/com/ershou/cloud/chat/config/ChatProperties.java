package com.ershou.cloud.chat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** 私聊参数（ershou.chat.*，与旧单体 ChatProperties 对齐；阈值全部来自 Nacos）。 */
@Data
@ConfigurationProperties(prefix = "ershou.chat")
public class ChatProperties {

    /** 总开关：关掉后 WebSocket 不再注册，前端自动退回 REST + 轮询 */
    private boolean enabled = true;

    /** 单条文本消息最大字数 */
    private int maxTextLength = 500;

    /** 历史消息默认条数 / 单次上限（游标翻页） */
    private int historySize = 20;
    private int maxHistorySize = 50;

    /**
     * 新号每日私聊限额开关。
     *
     * <p>默认关闭（与单体一致：2026-10-01 产品决定取消，新号每天 20 条太容易误伤）。
     * 打开后需要 user-service 内部接口返回 {@code createdAt/chatLimit}；拿不到则放行。
     */
    private boolean dailyLimitEnabled = false;

    /** 新号限额生效天数（仅在 dailyLimitEnabled=true 时有意义） */
    private int newUserDays = 7;

    /** 在线心跳有效期：连接/心跳时刷新，超过即视为离线 */
    private Duration onlineTtl = Duration.ofSeconds(90);

    /** 会话列表预览长度 */
    private int previewLength = 60;

    /** 撤回时限：只能撤回自己这段时间内发出的消息 */
    private Duration recallWindow = Duration.ofMinutes(2);

    /** 快捷话术（PRD FR-IM-02：卖家侧内置） */
    private List<String> quickReplies = List.of(
            "还在的", "可小刀", "支持当面验货", "XX 点见面可以吗", "已出，抱歉");
}
