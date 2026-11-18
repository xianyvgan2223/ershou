package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;

/**
 * Feign 返回体解包工具。
 *
 * <p>约定：下游 `/inner/**` 返回统一 {@link Result}。
 * <ul>
 *   <li>null（降级/超时）→ {@link ResultCode#SERVICE_UNAVAILABLE}；</li>
 *   <li>业务码非 0（如 30001 商品不存在、10003 无权限）→ 映射回 {@link ResultCode} 后抛出，
 *       前端拿到的 code 与单体/下游保持一致；common 里没有的码兜底为 90001 + 原始 message；</li>
 *   <li>data 正常返回。</li>
 * </ul>
 */
public final class FeignResults {

    private FeignResults() {
    }

    public static <T> T data(Result<T> result, String serviceName) {
        if (result == null) {
            throw new BizException(ResultCode.SERVICE_UNAVAILABLE, serviceName + "暂不可用，请稍后重试");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode()) {
            ResultCode rc = byCode(result.getCode());
            String message = result.getMessage() == null ? "下游调用失败" : result.getMessage();
            if (rc != null) {
                throw new BizException(rc, message);
            }
            throw new BizException(ResultCode.SYSTEM_ERROR, message);
        }
        return result.getData();
    }

    private static ResultCode byCode(int code) {
        for (ResultCode rc : ResultCode.values()) {
            if (rc.getCode() == code) {
                return rc;
            }
        }
        return null;
    }
}
