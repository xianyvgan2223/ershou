package com.ershou.campus.common.result;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(0, "ok"),
    PARAM_ERROR(10001, "参数校验失败"),
    NOT_LOGIN(10002, "未登录或登录已过期"),
    NO_PERMISSION(10003, "无权限访问"),
    RATE_LIMIT(10004, "操作过于频繁，请稍后再试"),

    SMS_CODE_ERROR(20001, "验证码错误或已过期"),
    USER_BANNED(20002, "账号已被限制，请联系客服"),
    PUBLISH_LIMIT(20003, "今日发布已达上限，明天再来吧"),
    CREDIT_LOW(20004, "信用分不足，暂时无法操作"),

    ITEM_NOT_FOUND(30001, "商品不存在或已删除"),
    ITEM_SOLD(30002, "商品已售出"),
    ITEM_AUDITING(30003, "商品正在审核中"),
    ITEM_LOCKED(30004, "商品刚被他人锁定，看看同款？"),
    ITEM_NOT_OWNER(30005, "只能操作自己发布的商品"),

    ORDER_NOT_FOUND(40001, "面交单不存在"),
    ORDER_NO_PERMISSION(40002, "无权操作该面交单"),
    ORDER_STATUS_ERROR(40003, "当前状态不允许此操作"),
    VERIFY_CODE_ERROR(40004, "核销码错误或已过期"),
    TIME_SLOT_INVALID(40005, "时间窗不合法（需在未来 7 天内、单次不超过 3 小时）"),

    CONTENT_VIOLATION(50001, "内容涉嫌违规，已拦截"),
    RISK_BLOCKED(50002, "该操作触发风控拦截"),

    SYSTEM_ERROR(90001, "系统开小差了，请稍后再试"),
    STORAGE_ERROR(90003, "文件存储服务不可用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
