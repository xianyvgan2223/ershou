package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 用户服务内部视图（/inner/users/** 契约）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InnerUserVO {

    private Long id;
    private String nickname;
    private String avatar;
    private String shopName;
    private Integer creditScore;
    private Integer roleFlags;
    private Boolean isAdmin;
    private Integer identityFlag;
    private Integer status;
    private Long schoolId;
    private Long campusId;
    private String createdAt;
}
