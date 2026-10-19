package com.ershou.cloud.common.result;

import lombok.Data;
import org.slf4j.MDC;

/**
 * 统一响应体（与单体 {@code com.ershou.campus.common.result.Result} 结构一致：
 * {@code {code,message,data,traceId}}，前端/App 无需改解析代码）。
 */
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        r.traceId = MDC.get("traceId");
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ResultCode rc) {
        return fail(rc.getCode(), rc.getMessage());
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.traceId = MDC.get("traceId");
        return r;
    }
}
