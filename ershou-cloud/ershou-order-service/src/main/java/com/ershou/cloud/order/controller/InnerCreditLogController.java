package com.ershou.cloud.order.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.order.dto.CreditLogRecordDTO;
import com.ershou.cloud.order.dto.CreditLogVO;
import com.ershou.cloud.order.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 信用分流水内部接口（{@code credit_log} 属 order-service 库）。
 *
 * <p>admin-service 人工调整信用分时：先调 user-service {@code POST /inner/users/{id}/credit?delta=}
 * 真正改分，再调本接口留痕（bizType=admin）—— 与旧单体 {@code AdminUserService.adjustCredit}
 * "改分 + 写 credit_log" 的语义对齐。
 */
@RestController
@RequestMapping("/inner/credit-logs")
@RequiredArgsConstructor
public class InnerCreditLogController {

    private final CreditService creditService;

    /** 查指定用户的信用分流水 */
    @GetMapping
    public Result<PageResult<CreditLogVO>> page(@RequestParam Long userId,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(creditService.page(userId, page, pageSize));
    }

    /** 运营人工调整后补流水 */
    @PostMapping
    public Result<Void> record(@RequestBody CreditLogRecordDTO dto) {
        creditService.recordAdminAdjust(dto);
        return Result.ok();
    }
}
