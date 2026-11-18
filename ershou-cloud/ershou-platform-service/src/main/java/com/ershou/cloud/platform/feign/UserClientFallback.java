package com.ershou.cloud.platform.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.feign.model.InnerCampusVO;
import com.ershou.cloud.platform.feign.model.InnerUserVO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户服务降级：不可用时统一返回 code=90001（管理员校验会因此拒绝，属安全侧 fail-closed）。
 */
@Component
public class UserClientFallback implements UserClient {

    private static final int CODE_UNAVAILABLE = 90001;
    private static final String MSG = "用户服务暂不可用，请稍后重试";

    @Override
    public Result<InnerUserVO> user(Long id) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<List<InnerUserVO>> users(String ids) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }

    @Override
    public Result<List<InnerCampusVO>> campuses(Long schoolId) {
        return Result.fail(CODE_UNAVAILABLE, MSG);
    }
}
