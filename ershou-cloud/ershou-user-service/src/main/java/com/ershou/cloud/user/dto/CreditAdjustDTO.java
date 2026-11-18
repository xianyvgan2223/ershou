package com.ershou.cloud.user.dto;

import lombok.Data;

/**
 * 内部接口：信用分调整入参（`POST /inner/users/{id}/credit`）。
 *
 * <p>`delta` 也可通过 query 参数传（`?delta=2`），两种都支持：
 * order-service 完成面交 +2 用 query 版，admin-service 人工调整带 `reason` 用 body 版。
 */
@Data
public class CreditAdjustDTO {

    /** 变动值：-100 ~ +100，且不能为 0 */
    private Integer delta;

    /** 变动原因（写日志用；信用流水 credit_log 在 ershou_order 库，由调用方落） */
    private String reason;
}
