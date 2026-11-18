package com.ershou.cloud.user.dto;

import lombok.Data;

/**
 * 内部接口：封禁 / 解封入参（`POST /inner/users/{id}/status`）。
 *
 * <p>契约最小集只要求 `{status}`；为与单体运营台的"默认封 7 天"语义对齐，
 * 额外支持可选的 `banDays`（status=3 时生效，不传则 ban_until 置空 = 永久封禁）。
 */
@Data
public class UserStatusDTO {

    /** 1=正常 2=限权 3=封禁 */
    private Integer status;

    /** 封禁天数（仅 status=3 时生效；不传 = 不写 ban_until） */
    private Integer banDays;

    /** 操作原因（只记日志；审计日志归 admin-service 的 sys_audit_log） */
    private String reason;
}
