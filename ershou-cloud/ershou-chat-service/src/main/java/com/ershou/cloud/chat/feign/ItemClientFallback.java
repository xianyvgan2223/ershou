package com.ershou.cloud.chat.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/** 商品服务不可用时的降级：返回 80001，会话的商品卡片降级为"商品已删除"占位。 */
@Slf4j
@Component
public class ItemClientFallback implements ItemClient {

    @Override
    public Result<ItemVO> get(Long id) {
        log.warn("[chat][feign][item] 获取商品 {} 降级", id);
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Override
    public Result<List<ItemVO>> batch(String ids) {
        log.warn("[chat][feign][item] 批量获取商品降级 ids={}", ids);
        return Result.fail(ResultCode.SERVICE_UNAVAILABLE);
    }
}
