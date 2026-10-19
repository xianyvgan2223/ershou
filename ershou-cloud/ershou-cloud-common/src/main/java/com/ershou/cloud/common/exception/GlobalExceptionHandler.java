package com.ershou.cloud.common.exception;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理（与单体行为一致）：
 * <ul>
 *   <li>{@link BizException} → 原样业务码；</li>
 *   <li>参数校验失败 → 10001 + 具体字段提示；</li>
 *   <li>Sa-Token 未登录/无权限 → 10002/10003（按类名识别，common 不硬依赖 sa-token，
 *       避免没有 sa-token 的 item/order 服务启动时因缺类失败）；</li>
 *   <li>其它 → 90001，日志带 traceId。</li>
 * </ul>
 * 由 {@code CloudCommonAutoConfiguration} 注册为 Bean（不扫描 common 包也能生效）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValid(Exception e) {
        String msg = ResultCode.PARAM_ERROR.getMessage();
        if (e instanceof MethodArgumentNotValidException ex && ex.getBindingResult().getFieldError() != null) {
            msg = ex.getBindingResult().getFieldError().getDefaultMessage();
        } else if (e instanceof BindException ex && ex.getBindingResult().getFieldError() != null) {
            msg = ex.getBindingResult().getFieldError().getDefaultMessage();
        }
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), msg);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadSize(MaxUploadSizeExceededException e) {
        return Result.fail(ResultCode.PARAM_ERROR.getCode(), "文件超过大小限制");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        String name = e.getClass().getName();
        // Sa-Token 的未登录/无权限异常：用类名判断，common 不需要编译期依赖 sa-token
        if (name.endsWith("NotLoginException")) {
            return Result.fail(ResultCode.NOT_LOGIN);
        }
        if (name.endsWith("NotRoleException") || name.endsWith("NotPermissionException")
                || name.endsWith("DisableServiceException")) {
            return Result.fail(ResultCode.NO_PERMISSION);
        }
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
