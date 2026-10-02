package com.ershou.campus.module.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.user.dto.UserProfileUpdateDTO;
import com.ershou.campus.module.user.service.UserBlockService;
import com.ershou.campus.module.user.service.UserService;
import com.ershou.campus.module.user.vo.PublicUserVO;
import com.ershou.campus.module.user.vo.UserBlockVO;
import com.ershou.campus.module.user.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserBlockService userBlockService;

    @Operation(summary = "当前登录用户信息")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.currentUser());
    }

    @Operation(summary = "编辑我的资料（昵称 / 头像 / 学校校区 / 小铺信息）")
    @PatchMapping("/me")
    public Result<UserVO> updateMe(@Valid @RequestBody UserProfileUpdateDTO dto) {
        return Result.ok(userService.updateProfile(StpUtil.getLoginIdAsLong(), dto));
    }

    @Operation(summary = "用户主页（公开；登录后附带拉黑状态）")
    @GetMapping("/{id}/profile")
    public Result<PublicUserVO> profile(@PathVariable Long id) {
        return Result.ok(userService.publicProfile(id, currentUserId()));
    }

    @Operation(summary = "拉黑用户（拉黑后双方不可私聊）")
    @PostMapping("/{id}/block")
    public Result<Void> block(@PathVariable Long id) {
        userBlockService.block(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "解除拉黑")
    @DeleteMapping("/{id}/block")
    public Result<Void> unblock(@PathVariable Long id) {
        userBlockService.unblock(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    @Operation(summary = "我拉黑的用户（带昵称/头像，可直接渲染列表）")
    @GetMapping("/blocks")
    public Result<List<UserBlockVO>> blocks() {
        return Result.ok(userBlockService.list(StpUtil.getLoginIdAsLong()));
    }

    /** 公开接口：登录了就用登录态，没登录按匿名处理 */
    private Long currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
