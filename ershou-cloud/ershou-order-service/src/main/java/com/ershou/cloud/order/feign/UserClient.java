package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 OpenFeign 客户端（full-split-contract 第三节 user-service 内部接口）。
 *
 * <ul>
 *   <li>{@code GET /inner/users/{id}} → 单个用户（面交单买卖家昵称、信用分快照）；</li>
 *   <li>{@code GET /inner/users/batch?ids=1,2} → 批量（列表 VO 避免 N+1）；</li>
 *   <li>{@code POST /inner/users/{id}/credit?delta=} → 信用分增减（"写自己的库"：sys_user 在 ershou_user）。</li>
 * </ul>
 * user-service 未就绪时 fallback 返回 90001：VO 里昵称降级为 null、信用分加分降级为"仅记流水"（记 warn）。
 */
@FeignClient(name = "ershou-user-service", contextId = "userClient", path = "/inner/users",
        fallback = UserClientFallback.class)
public interface UserClient {

    @GetMapping("/{id}")
    Result<InnerUserVO> get(@PathVariable("id") Long id);

    /** ids 逗号分隔，如 "1,2,3" */
    @GetMapping("/batch")
    Result<List<InnerUserVO>> batch(@RequestParam("ids") String ids);

    /**
     * 信用分增减：delta 正负均可（user-service 侧校验 -100~100 且非 0，并重算 credit_level）。
     * reason 仅用于下游日志，信用流水由本服务 credit_log 记录。
     */
    @PostMapping("/{id}/credit")
    Result<Integer> addCredit(@PathVariable("id") Long id,
                              @RequestParam("delta") Integer delta,
                              @RequestParam(value = "reason", required = false) String reason);
}
