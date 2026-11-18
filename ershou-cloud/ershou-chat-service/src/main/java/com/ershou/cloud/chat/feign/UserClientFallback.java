package com.ershou.cloud.chat.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** 用户服务不可用时的降级：返回 80001，chat-service 侧按 null/空集合处理。 */
@Slf4j
@Component
public class UserClientFallback implements UserClient {

    @Override
    public Result<UserVO> get(Long id) {
        log.warn("[chat][feign][user] 获取用户 {} 降级", id);
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<List<UserVO>> batch(String ids) {
        log.warn("[chat][feign][user] 批量获取用户降级 ids={}", ids);
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<BlockRelationVO> blocked(Long id, Long targetId) {
        log.warn("[chat][feign][user] 拉黑关系降级 {}->{}", id, targetId);
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }
}
