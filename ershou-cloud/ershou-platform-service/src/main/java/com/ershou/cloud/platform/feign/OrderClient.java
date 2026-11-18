package com.ershou.cloud.platform.feign;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.feign.model.InnerOrderVO;
import com.ershou.cloud.platform.feign.model.PlatformBuyResultVO;
import com.ershou.cloud.platform.feign.model.PlatformOrderCreateDTO;
import com.ershou.cloud.platform.feign.model.PlatformOrderSummaryVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订单服务 OpenFeign（第 8 轮全量拆分契约）。
 *
 * <p>平台单（meetup_order 中 seller_id=平台官方账号 的记录）由 order-service 落库，
 * platform-service 只做编排（券/秒杀库存 Lua + 订单创建 + 抢购记录）。
 */
@FeignClient(name = "ershou-order-service", contextId = "platformOrderClient", path = "/inner/orders",
        fallback = OrderClientFallback.class)
public interface OrderClient {

    /** 创建平台单（seller 固定平台官方账号；dealPrice 由平台侧计算传入） */
    @PostMapping("/platform")
    Result<PlatformBuyResultVO> createPlatformOrder(@RequestBody PlatformOrderCreateDTO dto);

    /** 平台单分页（运营台：只查平台官方账号作为卖家的订单） */
    @GetMapping("/platform")
    Result<PageResult<InnerOrderVO>> platformOrders(@RequestParam(value = "status", required = false) Integer status,
                                                    @RequestParam(value = "keyword", required = false) String keyword,
                                                    @RequestParam(value = "page", defaultValue = "1") long page,
                                                    @RequestParam(value = "pageSize", defaultValue = "10") long pageSize);

    /** 平台单概览（GMV/待自提等） */
    @GetMapping("/platform/summary")
    Result<PlatformOrderSummaryVO> platformSummary(@RequestParam(value = "campusId", required = false) Long campusId);
}
