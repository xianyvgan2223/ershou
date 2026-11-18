package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.UserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 管理列表的用户摘要补充（卖家/审核员昵称、信用分）。
 *
 * <p>item-service 的 /inner/items、/inner/item-reviews 只给 sellerId，管理端列表要显示卖家昵称；
 * 这里批量调 user-service /inner/users/batch 一次补齐，user-service 不可用时容忍为空（列表照常返回）。
 *
 * <p>注意：user-service 的 InnerUserVO 里**没有手机号**（手机号只在管理列表接口里），
 * 所以 admin 的商品/审核列表 sellerPhone 暂为空 —— 已列入交付报告遗留（建议 user 侧给内部接口加
 * withPhone 或 admin 专用单查），不要为此跨库查 sys_user。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserDirectory {

    private final UserClient userClient;

    /** ids → {nickname, creditScore, avatar...}；失败返回空 map */
    public Map<Long, Map<String, Object>> briefs(Collection<Long> ids) {
        Map<Long, Map<String, Object>> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        String joined = ids.stream().filter(Objects::nonNull).distinct()
                .map(String::valueOf).collect(Collectors.joining(","));
        if (joined.isEmpty()) {
            return map;
        }
        try {
            List<Map<String, Object>> users = FeignResults.data(userClient.batchUsers(joined), "用户服务");
            if (users != null) {
                for (Map<String, Object> user : users) {
                    Long id = MapHelper.lng(user, "id");
                    if (id != null) {
                        map.put(id, user);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[admin][user-directory] 补充用户摘要失败（容忍）：{}", e.getMessage());
        }
        return map;
    }

    /** 从摘要里取字段 */
    public String nickname(Map<Long, Map<String, Object>> briefs, Long userId) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> brief = briefs.get(userId);
        return brief == null ? null : MapHelper.str(brief, "nickname");
    }

    public Integer creditScore(Map<Long, Map<String, Object>> briefs, Long userId) {
        if (userId == null) {
            return null;
        }
        Map<String, Object> brief = briefs.get(userId);
        return brief == null ? null : MapHelper.intg(brief, "creditScore");
    }
}
