package com.ershou.cloud.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 抢券结果（设计 3.3，字段与 Web `api/platform.ts#CouponClaimVO` 对齐）。
 *
 * <p>{@code code} 是<b>业务子码</b>，不是 Result.code：
 * 1 成功 / 0 领完 / -1 未预热 / -2 超限 / -3 未开始或已结束 / -4 限流。
 * 失败也返回 200 + success=false，由前端按子码给文案。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponClaimVO {
    private Boolean success;
    private Integer code;
    private String message;
    /** 领取后的剩余库存（失败时为当前值，便于前端直接刷新按钮数字） */
    private Integer leftStock;

    public static CouponClaimVO of(int code, String message, int leftStock) {
        return new CouponClaimVO(code == 1, code, message, Math.max(leftStock, 0));
    }
}
