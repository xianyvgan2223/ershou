package com.ershou.cloud.item.review;

import lombok.Data;

/** 上架审核页「下架」请求体 */
@Data
public class ReviewOfflineDTO {

    private String reason;
}
