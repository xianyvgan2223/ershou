package com.ershou.cloud.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.user.service.UserService;
import com.ershou.cloud.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口（`GET /api/v1/users/me` 字段与单体完全一致）。
 *
 * <p>用户身份来源优先级（两条都是同一个 Sa-Token 会话，只是入口不同）：
 * <ol>
 *   <li><b>网关链路（主）</b>：网关 8088 校验 token 后注入 `X-User-Id`，
 *       common 的 {@code UserContextInterceptor} 自动写进 {@link UserContext}，
 *       业务代码用 {@link UserContext#requireUserId()} 取值（不在 Controller 里摸 header）；
 *   <li><b>直连本服务（联调/验证）</b>：没有网关注入时回退用 Sa-Token 解 `Authorization`
 *       —— 会话存在共享 Redis db0，所以单体 8080 发的 token 这里也能认（会话互通演示点）。
 * </ol>
 * 两者都没有 → 10002 未登录。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.currentUser(resolveUserId()));
    }

    /** 网关透传的 X-User-Id（已进 UserContext）优先；直连时用 Sa-Token 兜底 */
    private Long resolveUserId() {
        if (UserContext.isLogin()) {
            return UserContext.requireUserId();
        }
        if (StpUtil.isLogin()) {
            return StpUtil.getLoginIdAsLong();
        }
        throw new BizException(ResultCode.NOT_LOGIN);
    }
}
