package com.ershou.cloud.user.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * item-service 的卖家摘要（只用于校验 sellerId 过滤是否生效）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SellerBriefVO {

    private Long id;
}
