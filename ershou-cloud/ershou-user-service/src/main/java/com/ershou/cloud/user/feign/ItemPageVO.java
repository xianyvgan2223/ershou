package com.ershou.cloud.user.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * item-service 商品分页响应（只反序列化本服务用到的字段）。
 *
 * <p>只关心 {@code total} 与第一条记录的 {@code seller.id}（做 sellerId 过滤是否生效的防御校验），
 * 其余字段用 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 忽略。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemPageVO {

    private long total;
    private long pageNum;
    private long pageSize;
    private List<ItemBriefVO> records;
}
