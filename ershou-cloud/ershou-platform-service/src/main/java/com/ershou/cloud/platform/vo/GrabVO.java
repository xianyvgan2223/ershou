package com.ershou.cloud.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 抢购结果（设计 3.3，字段与 Web `api/platform.ts#GrabVO` 对齐）。
 * code 语义同抢券：1 成功 / 0 抢完 / -1 未预热 / -2 超限 / -3 未开始或已结束 / -4 限流。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrabVO {
    private Boolean success;
    private Integer code;
    private String message;
    private String orderNo;
    private Long orderId;

    public static GrabVO of(int code, String message) {
        return new GrabVO(code == 1, code, message, null, null);
    }
}
