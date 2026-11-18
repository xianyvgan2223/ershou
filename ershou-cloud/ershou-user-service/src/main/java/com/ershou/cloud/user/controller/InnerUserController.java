package com.ershou.cloud.user.controller;

import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.user.dto.CreditAdjustDTO;
import com.ershou.cloud.user.dto.UserStatusDTO;
import com.ershou.cloud.user.service.UserBlockService;
import com.ershou.cloud.user.service.UserService;
import com.ershou.cloud.user.vo.BlockedStateVO;
import com.ershou.cloud.user.vo.InnerUserAdminVO;
import com.ershou.cloud.user.vo.InnerUserVO;
import com.ershou.cloud.user.vo.UserStatsVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户域内部接口（`/inner/users/**`，仅供其他微服务 Feign 调用，不经网关对外暴露）。
 *
 * <p>契约见 `ershou-cloud/docs/full-split-contract.md` 第三节 + 第六节（T107 追加）：
 * <ul>
 *   <li>{@code GET /inner/users/{id}} → 单个用户（item/order/chat/admin 都要）；</li>
 *   <li>{@code GET /inner/users/batch?ids=1,2} → 批量（order 面交单买卖双方昵称）；</li>
 *   <li>{@code GET /inner/users} → 管理端用户列表（admin-service）；</li>
 *   <li>{@code GET /inner/users/stats} → 用户统计（admin 概览）；</li>
 *   <li>{@code POST /inner/users/{id}/status} → 封禁/解封（admin-service）；</li>
 *   <li>{@code POST /inner/users/{id}/credit} → 信用分调整（order 完成面交 +2 / admin 人工调整）；</li>
 *   <li>{@code GET /inner/users/{id}/blocked?targetId=} → 双向拉黑状态（chat 私聊校验）。</li>
 * </ul>
 *
 * <p>这些接口不做登录校验：服务间调用方（网关之外的内部链路）负责身份/权限判断，
 * 例如 admin-service 在调用前已经校验过操作者是管理员。端口只应内网可达。
 */
@RestController
@RequestMapping("/inner/users")
@RequiredArgsConstructor
public class InnerUserController {

    private final UserService userService;
    private final UserBlockService userBlockService;

    /** 单个用户：{id,nickname,avatar,creditScore,identityFlag,roleFlags,isAdmin,status,schoolName,campusName,...} */
    @GetMapping("/{id}")
    public Result<InnerUserVO> get(@PathVariable Long id) {
        return Result.ok(userService.innerUser(id));
    }

    /** 批量取用户：ids 逗号分隔（保持入参顺序，忽略不存在的 id） */
    @GetMapping("/batch")
    public Result<List<InnerUserVO>> batch(@RequestParam("ids") String ids) {
        return Result.ok(userService.innerUsers(parseIds(ids)));
    }

    /**
     * 管理端用户列表。
     *
     * <p>分页参数用 `page`（1 开始）/`pageSize`，与契约一致；额外支持可选的 `status` 过滤
     * （单体运营后台支持按状态筛，admin-service 传了就生效）。
     */
    @GetMapping
    public Result<PageResult<InnerUserAdminVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(name = "page", defaultValue = "1") long page,
            @RequestParam(name = "pageSize", defaultValue = "20") long pageSize) {
        return Result.ok(userService.adminPage(keyword, status, page, pageSize));
    }

    /** 用户统计：{total,banned,newToday} */
    @GetMapping("/stats")
    public Result<UserStatsVO> stats() {
        return Result.ok(userService.stats());
    }

    /** 封禁 / 解封：body {status}（1 正常 / 2 限权 / 3 封禁），可选 banDays/reason */
    @PostMapping("/{id}/status")
    public Result<InnerUserVO> updateStatus(@PathVariable Long id, @RequestBody UserStatusDTO dto) {
        return Result.ok(userService.updateStatus(id, dto));
    }

    /**
     * 信用分调整（query 参数版，order-service 完成面交 +2 用）。
     *
     * <p>不解析 body：某些 HTTP 客户端 POST 时会带 `application/x-www-form-urlencoded` 空 body，
     * 若这里声明 `@RequestBody` 会被 Spring 以"不支持的 Content-Type"拒绝（90001）。
     * 用 `params = "delta"` 把"query 版"和"JSON body 版"拆成两个映射，各自只处理自己的入参形态。
     */
    @PostMapping(value = "/{id}/credit", params = "delta")
    public Result<Integer> creditByQuery(@PathVariable Long id,
                                         @RequestParam("delta") Integer delta) {
        return Result.ok(userService.adjustCredit(id, delta, null));
    }

    /**
     * 信用分调整（JSON body 版，admin-service 人工调整用）。
     *
     * <p>body：`{"delta":2,"reason":"完成面交"}`；返回受影响行数（1 成功；用户不存在 → 10001）。
     */
    @PostMapping(value = "/{id}/credit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<Integer> creditByBody(@PathVariable Long id,
                                        @RequestBody(required = false) CreditAdjustDTO body) {
        Integer delta = body == null ? null : body.getDelta();
        String reason = body == null ? null : body.getReason();
        return Result.ok(userService.adjustCredit(id, delta, reason));
    }

    /** 双向拉黑状态：{blockedByMe, blockedMe}（chat-service 私聊/拉黑校验用） */
    @GetMapping("/{id}/blocked")
    public Result<BlockedStateVO> blocked(@PathVariable Long id,
                                          @RequestParam("targetId") Long targetId) {
        BlockedStateVO vo = new BlockedStateVO();
        vo.setBlockedByMe(userBlockService.blocked(id, targetId));
        vo.setBlockedMe(userBlockService.blocked(targetId, id));
        return Result.ok(vo);
    }

    /** 解析 "1,2,3"；非法项直接抛 10001（内部调用方传错参数应当立刻暴露） */
    private List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                throw new BizException(ResultCode.PARAM_ERROR, "ids 参数格式不正确：" + s);
            }
        }
        return ids;
    }
}
