package com.ershou.cloud.admin.client;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * user-service 内部接口（管理员校验 / 用户管理 / 学校校区）。
 *
 * <p>返回体用 {@code Map} 透传，避免在 admin-service 里复制一份用户 DTO；
 * 需要计算展示字段（statusText/creditLevelText 等）时在 AdminUserService 里补。
 * 与 auth-member（T101）实际实现对齐（2026-11-13）。
 */
@FeignClient(name = "ershou-user-service", contextId = "adminUserClient", path = "/inner",
        fallback = UserClientFallback.class)
public interface UserClient {

    /** 单用户（含 isAdmin/roleFlags/status）——管理员校验用 */
    @GetMapping("/users/{id}")
    Result<Map<String, Object>> getUser(@PathVariable("id") Long id);

    /** 批量用户（管理列表/商品与订单补充卖家昵称用） */
    @GetMapping("/users/batch")
    Result<List<Map<String, Object>>> batchUsers(@RequestParam("ids") String ids);

    /** 管理端用户列表（keyword 昵称/店铺名/手机号；分页参数 page/pageSize） */
    @GetMapping("/users")
    Result<PageResult<Map<String, Object>>> pageUsers(@RequestParam("keyword") String keyword,
                                                      @RequestParam("status") Integer status,
                                                      @RequestParam("page") long page,
                                                      @RequestParam("pageSize") long pageSize);

    /** 用户统计：{total,banned,newToday} */
    @GetMapping("/users/stats")
    Result<Map<String, Object>> userStats();

    /** 封禁/解封/限权（user-service 内部负责会话与落库） */
    @PostMapping("/users/{id}/status")
    Result<Map<String, Object>> updateStatus(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    /** 信用分调整：body {delta,reason}，返回受影响行数（1 成功） */
    @PostMapping("/users/{id}/credit")
    Result<Integer> adjustCredit(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    /** 学校列表 */
    @GetMapping("/schools")
    Result<List<Map<String, Object>>> schools();

    /** 校区列表（schoolId 可选 = 全部校区） */
    @GetMapping("/campuses")
    Result<List<Map<String, Object>>> campuses(@RequestParam(name = "schoolId", required = false) Long schoolId);
}
