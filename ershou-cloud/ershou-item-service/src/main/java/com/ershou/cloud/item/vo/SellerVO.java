package com.ershou.cloud.item.vo;

import lombok.Data;

/**
 * 商品卖家摘要（与单体 SellerVO 同结构）。
 *
 * <p>云 item-service 只连 {@code ershou_item} 库，跨服务拿不到用户昵称/头像：
 * 本轮只回填 {@code id}，其余字段为 null（Web 端 {@code seller?.nickname || '同学'} 有兜底）。
 * 若演示需要真实昵称，可由 order-service 侧 Feign user-service 补全，或后续给 item-service 加内部 Feign。
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
