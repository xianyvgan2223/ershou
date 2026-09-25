package com.ershou.campus.module.item.vo;

import lombok.Data;

@Data
public class SellerVO {
    private Long id;
    private String nickname;
    private String avatar;
    private Integer creditScore;
    private Integer identityFlag;
    private String shopName;
}
