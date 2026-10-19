package com.ershou.cloud.common.exception;

import com.ershou.cloud.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常（与单体 {@code BizException} 一致，GlobalExceptionHandler 统一转成 Result）。
 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BizException(ResultCode rc, String message) {
        super(message);
        this.code = rc.getCode();
    }

    public BizException(ResultCode rc, Throwable cause) {
        super(rc.getMessage(), cause);
        this.code = rc.getCode();
    }

    /** 下游服务降级的快捷构造（Feign fallback 用） */
    public static BizException serviceUnavailable(String message) {
        return new BizException(ResultCode.SERVICE_UNAVAILABLE, message);
    }
}
