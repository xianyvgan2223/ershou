package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.stereotype.Component;

/** {@link PointClient} 的 Sentinel 降级实现：地图服务不可用时点位名留空、点位校验降级。 */
@Component
public class PointClientFallback implements PointClient {

    public static final int CODE_MAP_SERVICE_UNAVAILABLE = 90001;

    @Override
    public Result<InnerPointVO> get(Long id) {
        return Result.fail(CODE_MAP_SERVICE_UNAVAILABLE, "地图服务暂不可用");
    }
}
