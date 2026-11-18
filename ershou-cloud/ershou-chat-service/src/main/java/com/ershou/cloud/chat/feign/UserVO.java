package com.ershou.cloud.chat.feign;

import lombok.Data;

import java.time.LocalDateTime;

/** user-service 内部用户视图（契约：/inner/users/{id}，字段追加式兼容）。 */
@Data
public class UserVO {

    private Long id;
    private String nickname;
    private String avatar;
    private Integer creditScore;
    private Integer identityFlag;
    private Integer roleFlags;
    private Integer status;
    /** 新号限额用（可选返回；缺失时 chat-service 不做限额） */
    private LocalDateTime createdAt;
    private Integer chatLimit;
}
