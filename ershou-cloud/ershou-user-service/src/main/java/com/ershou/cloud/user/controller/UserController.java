package com.ershou.cloud.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.user.dto.UserProfileUpdateDTO;
import com.ershou.cloud.user.service.UserBlockService;
import com.ershou.cloud.user.service.UserService;
import com.ershou.cloud.user.vo.PublicUserVO;
import com.ershou.cloud.user.vo.UserBlockVO;
import com.ershou.cloud.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户接口（T101 全量迁移：路径 / 参数 / 响应 JSON 与单体 `module/user/controller/UserController` 一致）。
 *
 * <p>用户身份来源：网关 8088 校验 token 后注入 `X-User-Id`（common 的 UserContextInterceptor 写进
 * {@link UserContext}）；直连本服务联调时用 Sa-Token 从 `Authorization` 头解（会话在共享 Redis db0，
 * 单体 8080 / 网关 8088 发的 token 这里都认）。两者都没有 → 10002。
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserBlockService userBlockService;

    /** 当前登录用户信息 */
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.currentUser(resolveUserId()));
    }

    /** 编辑我的资料（昵称 / 头像 / 学校校区 / 小铺信息） */
    @PatchMapping("/me")
    public Result<UserVO> updateMe(@Valid @RequestBody UserProfileUpdateDTO dto) {
        return Result.ok(userService.updateProfile(resolveUserId(), dto));
    }

    /** 用户主页（公开；登录后附带拉黑状态） */
    @GetMapping("/{id}/profile")
    public Result<PublicUserVO> profile(@PathVariable Long id) {
        return Result.ok(userService.publicProfile(id, resolveUserIdOrNull()));
    }

    /** 我拉黑的用户（带昵称/头像，可直接渲染列表） */
    @GetMapping("/blocks")
    public Result<List<UserBlockVO>> blocks() {
        return Result.ok(userBlockService.list(resolveUserId()));
    }

    /** 拉黑用户（拉黑后双方不可私聊） */
    @PostMapping("/{id}/block")
    public Result<Void> block(@PathVariable Long id) {
        userBlockService.block(resolveUserId(), id);
        return Result.ok();
    }

    /** 解除拉黑 */
    @DeleteMapping("/{id}/block")
    public Result<Void> unblock(@PathVariable Long id) {
        userBlockService.unblock(resolveUserId(), id);
        return Result.ok();
    }

    /* ------------------------------------------------------------------ *
     * 取当前用户 id：网关透传的 X-User-Id 优先；直连时用 Sa-Token 兜底
     * ------------------------------------------------------------------ */

    /** 必须登录的接口用：取不到抛 10002 */
    private Long resolveUserId() {
        Long userId = resolveUserIdOrNull();
        if (userId == null) {
            throw new BizException(ResultCode.NOT_LOGIN);
        }
        return userId;
    }

    /** 公开接口用：登录了就带上（拉黑/self 判断），没登录按匿名处理 */
    private Long resolveUserIdOrNull() {
        if (UserContext.isLogin()) {
            return UserContext.getUserId();
        }
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (Exception e) {
            // Sa-Token 无上下文（极少见）时按匿名处理
            return null;
        }
    }
}
