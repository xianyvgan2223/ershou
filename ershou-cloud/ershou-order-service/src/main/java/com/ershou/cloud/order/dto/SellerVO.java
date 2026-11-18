package com.ershou.cloud.order.dto;

import lombok.Data;

/**
 * 卖家简要信息（旧单体 {@code module.item.vo.SellerVO} 同名字段，前端 ItemCard/详情页用）。
 * 数据来自 user-service {@code GET /inner/users/{id}}；user-service 未就绪时昵称留空（降级）。
 */
@Data
public class SellerVO {

    private Long id;

    private String nickname;

    private String avatar;

    private Integer creditScore;

    private Integer identityFlag;

    private String shopName;
}
