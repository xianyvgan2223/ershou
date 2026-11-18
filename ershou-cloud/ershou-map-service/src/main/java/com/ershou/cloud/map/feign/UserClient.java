package com.ershou.cloud.map.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 OpenFeign 客户端：只读校区底图配置（campus 表在 ershou_user 库，禁止跨库 SQL）。
 *
 * <p>契约（full-split-contract.md 第三节）：{@code GET /inner/campuses?schoolId=} → 校区列表（含
 * {@code mapType/mapConfig} 全字段）；不传 schoolId 为全部校区。schoolId 走 Nacos
 * {@code ershou.map.default-school-id}（演示环境只有一个学校）。
 */
@FeignClient(name = "ershou-user-service", contextId = "mapUserClient", path = "/inner",
        fallback = UserClientFallback.class)
public interface UserClient {

    @GetMapping("/campuses")
    Result<List<CampusVO>> campuses(@RequestParam(value = "schoolId", required = false) Long schoolId);
}
