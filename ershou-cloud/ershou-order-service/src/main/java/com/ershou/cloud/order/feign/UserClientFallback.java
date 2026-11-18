package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link UserClient} 的 Sentinel 降级实现：用户服务不可用时 VO 昵称留空、信用分加分降级为仅记流水。 */
@Component
public class UserClientFallback implements UserClient {

    public static final int CODE_USER_SERVICE_UNAVAILABLE = 90001;

    @Override
    public Result<InnerUserVO> get(Long id) {
        return Result.fail(CODE_USER_SERVICE_UNAVAILABLE, "用户服务暂不可用");
    }

    @Override
    public Result<List<InnerUserVO>> batch(String ids) {
        return Result.fail(CODE_USER_SERVICE_UNAVAILABLE, "用户服务暂不可用");
    }

    @Override
    public Result<Integer> addCredit(Long id, Integer delta, String reason) {
        return Result.fail(CODE_USER_SERVICE_UNAVAILABLE, "用户服务暂不可用");
    }
}
