package com.ershou.campus.common.exception;

import com.ershou.campus.common.result.ResultCode;
import lombok.Getter;

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
}
