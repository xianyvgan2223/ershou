package com.ershou.cloud.platform.feign;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.feign.model.InnerCampusVO;
import com.ershou.cloud.platform.feign.model.InnerUserVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 用户服务 OpenFeign（第 8 轮全量拆分契约）。
 *
 * <p>平台服务需要：管理员判定（isAdmin/roleFlags）、买家昵称（平台订单列表）、校区名（代卖点列表）。
 */
@FeignClient(name = "ershou-user-service", contextId = "platformUserClient", path = "/inner",
        fallback = UserClientFallback.class)
public interface UserClient {

    /** 单个用户（含 isAdmin/roleFlags 等运营权限判定字段） */
    @GetMapping("/users/{id}")
    Result<InnerUserVO> user(@PathVariable("id") Long id);

    /** 批量用户（ids 逗号分隔；平台订单列表补买家昵称，N+1 收敛） */
    @GetMapping("/users/batch")
    Result<List<InnerUserVO>> users(@RequestParam("ids") String ids);

    /** 校区列表（可带 schoolId 过滤；代卖点列表显示校区名） */
    @GetMapping("/campuses")
    Result<List<InnerCampusVO>> campuses(@RequestParam(value = "schoolId", required = false) Long schoolId);
}
