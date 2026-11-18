package com.ershou.cloud.map.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link UserClient} 的 Sentinel 降级实现：user-service 不可用时返回 80001。
 *
 * <p>业务层（CampusConfigService）捕获后降级为"无底图配置"（mapType 默认 1、mapConfig=null），
 * `/map/points` 仍返回 code=0，前端退化为自绘平面图，不阻塞地图页。
 */
@Component
public class UserClientFallback implements UserClient {

    @Override
    public Result<List<CampusVO>> campuses(Long schoolId) {
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE.getCode(), "用户服务暂不可用");
    }
}
