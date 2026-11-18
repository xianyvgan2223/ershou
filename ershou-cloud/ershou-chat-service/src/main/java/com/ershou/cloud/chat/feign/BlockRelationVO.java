package com.ershou.cloud.chat.feign;

import lombok.Data;

/** 拉黑关系（user-service 内部接口 /inner/users/{id}/blocked?targetId= 的 data 结构）。 */
@Data
public class BlockRelationVO {

    /** 我是否拉黑了 targetId */
    private Boolean blockedByMe;
    /** targetId 是否拉黑了我 */
    private Boolean blockedMe;
}
