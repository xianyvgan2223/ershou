package com.ershou.cloud.user.service;

import com.ershou.cloud.user.entity.SysUser;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 运营身份判定（与单体 {@code AdminAuthService} 口径完全一致）。
 *
 * <p>单体从 {@code AdminProperties} 读 {@code ershou.admin.role-flag}（默认 8）与
 * {@code ershou.admin.user-ids}（逗号分隔白名单）；微服务把这两项放在本服务 Nacos 配置里，
 * 判定顺序也保持一致：先看白名单，再看 {@code role_flags & roleFlag != 0}。
 *
 * <p>为什么要保留白名单：演示/应急时运营权限可通过配置直接授予某个用户 id（不改库），
 * admin-service 调用 {@code /inner/users/{id}} 拿到的 {@code isAdmin} 必须与单体一致。
 */
@Service
public class AdminCheckService {

    @Getter
    private final int roleFlag;

    private final Set<Long> userIds = new LinkedHashSet<>();

    public AdminCheckService(@Value("${ershou.admin.role-flag:8}") int roleFlag,
                             @Value("${ershou.admin.user-ids:}") String userIdsCsv) {
        this.roleFlag = roleFlag;
        if (StringUtils.hasText(userIdsCsv)) {
            for (String part : userIdsCsv.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    userIds.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // 配置写错一个 id 不该影响服务启动：跳过非法项即可
                }
            }
        }
    }

    /** 是否运营人员（user 可为 null） */
    public boolean isAdmin(SysUser user) {
        if (user == null) {
            return false;
        }
        if (user.getId() != null && userIds.contains(user.getId())) {
            return true;
        }
        return user.getRoleFlags() != null && (user.getRoleFlags() & roleFlag) != 0;
    }

    /** 配置的来源（日志/排查用）：白名单 id 列表 */
    public Set<Long> getWhitelist() {
        return Set.copyOf(userIds);
    }
}
