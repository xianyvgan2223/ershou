package com.ershou.campus.module.admin.service;

import cn.dev33.satoken.stp.StpUtil;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.module.admin.config.AdminProperties;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 运营权限判定。全站唯一的"是不是管理员"入口，避免各处重复实现。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private final SysUserMapper userMapper;
    private final AdminProperties adminProperties;

    public boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        if (adminProperties.getUserIds().contains(userId)) {
            return true;
        }
        return isAdmin(userMapper.selectById(userId));
    }

    /** 已拿到用户对象时可直接判定，省一次查询 */
    public boolean isAdmin(SysUser user) {
        if (user == null) {
            return false;
        }
        if (user.getId() != null && adminProperties.getUserIds().contains(user.getId())) {
            return true;
        }
        return user.getRoleFlags() != null && (user.getRoleFlags() & adminProperties.getRoleFlag()) != 0;
    }

    /** 校验当前登录用户是运营人员，返回其用户 id */
    public Long requireAdminId() {
        StpUtil.checkLogin();
        Long userId = StpUtil.getLoginIdAsLong();
        if (!isAdmin(userId)) {
            throw new BizException(ResultCode.NO_PERMISSION, "需要运营人员权限");
        }
        return userId;
    }
}
