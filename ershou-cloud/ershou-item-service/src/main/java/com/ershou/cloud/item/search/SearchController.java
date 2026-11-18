package com.ershou.cloud.item.search;

import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.common.web.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品搜索运维接口（契约：{@code /api/v1/search/**} 路由到 item-service）。
 *
 * <p>拆分后本服务不接 token（网关统一校验并注入 {@code X-User-Id}），所以管理员校验改为：
 * <ul>
 *   <li>必须登录（{@code X-User-Id} 存在），否则 10002；</li>
 *   <li>配置了 {@code ershou.item.reindex-user-ids} 时，userId 必须在白名单内，否则 10003。</li>
 * </ul>
 * 旧单体的 {@code AdminAuthService.requireAdminId()} 依赖用户表（在 ershou_user 库），item-service 不跨库查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final ItemIndexService itemIndexService;

    /** 允许触发重建的用户 id 白名单（逗号分隔；空=登录即可，演示环境） */
    @Value("${ershou.item.reindex-user-ids:}")
    private String reindexUserIds;

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.ok(itemIndexService.status());
    }

    @PostMapping("/reindex")
    public Result<Map<String, Object>> reindex(
            @RequestParam(defaultValue = "false") boolean sync) {
        Long operatorId = requireOperator();
        log.info("[search][es] {} 触发全量重建（sync={}）", operatorId, sync);
        // 默认异步：数据量大时不阻塞请求。sync=true 给"跑完要立刻断言索引一致"的测试/脚本用。
        if (sync) {
            return Result.ok(itemIndexService.reindexAll());
        }
        return Result.ok(itemIndexService.reindexAllAsync());
    }

    /** 登录 + 可选白名单校验 */
    private Long requireOperator() {
        Long userId = UserContext.requireUserId();
        Set<Long> allowed = Arrays.stream(reindexUserIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).collect(Collectors.toSet());
        if (!allowed.isEmpty() && !allowed.contains(userId)) {
            throw new BizException(ResultCode.NO_PERMISSION, "仅运营人员可重建商品索引");
        }
        return userId;
    }
}
