package com.ershou.cloud.user.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * item-service 列表里的商品摘要（只取 id + seller.id）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemBriefVO {

    private Long id;

    /** 卖家信息（item-service 列表里 seller 只填 id） */
    private SellerBriefVO seller;
}
