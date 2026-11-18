package com.ershou.cloud.user.vo;

import lombok.Data;

/**
 * 双向拉黑状态（`GET /inner/users/{id}/blocked?targetId=`，chat-service 私聊/拉黑校验用）。
 *
 * <p>`user_block` 是单向存储、双向生效：chat-service 判断"能不能发消息"时任一方向为 true 即拦截，
 * 但界面提示需要区分"我拉黑了对方"和"对方拉黑了我"。
 */
@Data
public class BlockedStateVO {

    /** user_id 是否拉黑了 targetId（我拉黑对方） */
    private Boolean blockedByMe;

    /** targetId 是否拉黑了 user_id（对方拉黑我） */
    private Boolean blockedMe;
}
