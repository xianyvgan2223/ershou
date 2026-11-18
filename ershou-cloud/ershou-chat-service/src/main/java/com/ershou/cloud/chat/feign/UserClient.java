package com.ershou.cloud.chat.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 OpenFeign 客户端（full-split-contract 第三节 user-service 内部接口）。
 *
 * <ul>
 *   <li>{@code GET /inner/users/{id}} → 昵称/头像/信用分/身份徽章（会话列表展示）；</li>
 *   <li>{@code GET /inner/users/batch?ids=1,2} → 批量；</li>
 *   <li>{@code GET /inner/users/{id}/blocked?targetId=} → 拉黑关系（本轮与 Lead/auth-member 协调确认的契约），
 *       返回 {@code {blockedByMe, blockedMe}}。</li>
 * </ul>
 *
 * <p>服务不可用/接口未就绪时走 fallback（code=80001）：chat-service 降级为"对方信息为空、未拉黑、不限额"，
 * 会话/消息主链路不受影响。
 */
@FeignClient(name = "ershou-user-service", contextId = "chatUserClient", path = "/inner/users",
        fallback = UserClientFallback.class)
public interface UserClient {

    @GetMapping("/{id}")
    Result<UserVO> get(@PathVariable("id") Long id);

    @GetMapping("/batch")
    Result<List<UserVO>> batch(@RequestParam("ids") String ids);

    @GetMapping("/{id}/blocked")
    Result<BlockRelationVO> blocked(@PathVariable("id") Long id, @RequestParam("targetId") Long targetId);
}
