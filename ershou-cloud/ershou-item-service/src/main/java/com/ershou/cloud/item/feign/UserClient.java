package com.ershou.cloud.item.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * user-service 内部接口（契约 §三 + T102 追加字段）。
 *
 * <p>item-service 只读用户数据（卖家摘要 / 发布落学校校区 / 新号限额），不写用户库。
 * 调用方 {@code RemoteService} 统一 catch 异常降级，服务不可用不影响商品读接口。
 */
@FeignClient(name = "ershou-user-service", contextId = "itemUserClient", path = "/inner/users")
public interface UserClient {

    @GetMapping("/{id}")
    Result<InnerUserVO> get(@PathVariable("id") Long id);

    @GetMapping("/batch")
    Result<List<InnerUserVO>> batch(@RequestParam("ids") String ids);
}
