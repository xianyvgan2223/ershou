package com.ershou.cloud.platform.feign;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.platform.feign.model.InnerItemVO;
import com.ershou.cloud.platform.feign.model.PlatformItemSummaryVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * 商品服务 OpenFeign（第 8 轮全量拆分契约）。
 *
 * <ul>
 *   <li>已有：{@code GET /inner/items/{id}}；</li>
 *   <li>本轮新增（item-service T102）：{@code /inner/items/platform/**} 平台商品列表/详情/创建/编辑/上下架/汇总，
 *       {@code /inner/items/batch} 批量详情（秒杀商品补全）；</li>
 *   <li>服务不可用时走 {@link ItemClientFallback}（Sentinel），返回统一 code=90001。</li>
 * </ul>
 */
@FeignClient(name = "ershou-item-service", contextId = "platformItemClient", path = "/inner/items",
        fallback = ItemClientFallback.class)
public interface ItemClient {

    /** 单商品详情（已有接口，云版字段只含基础字段） */
    @GetMapping("/{id}")
    Result<InnerItemVO> get(@PathVariable("id") Long id);

    /** 批量商品详情（秒杀商品补全用；ids 逗号分隔） */
    @GetMapping("/batch")
    Result<List<InnerItemVO>> batch(@RequestParam("ids") String ids);

    /** 平台商品运营台分页（含全部状态，支持关键词/代卖点/校区/分类筛选与排序） */
    @GetMapping("/platform")
    Result<PageResult<InnerItemVO>> platformPage(@RequestParam(value = "campusId", required = false) Long campusId,
                                                 @RequestParam(value = "pointId", required = false) Long pointId,
                                                 @RequestParam(value = "categoryId", required = false) Long categoryId,
                                                 @RequestParam(value = "status", required = false) Integer status,
                                                 @RequestParam(value = "keyword", required = false) String keyword,
                                                 @RequestParam(value = "sort", required = false) String sort,
                                                 @RequestParam(value = "page", defaultValue = "1") long page,
                                                 @RequestParam(value = "pageSize", defaultValue = "10") long pageSize);

    /** 平台商品详情（运营台编辑回显 / 发布返回） */
    @GetMapping("/platform/{id}")
    Result<InnerItemVO> platformDetail(@PathVariable("id") Long id);

    /** 平台商品创建（body 同单体发布 DTO） */
    @PostMapping("/platform")
    Result<Map<String, Object>> platformCreate(@RequestBody Map<String, Object> body);

    /** 平台商品编辑（标题/价格/库存/代卖点/分类等） */
    @PutMapping("/platform/{id}")
    Result<InnerItemVO> platformUpdate(@PathVariable("id") Long id, @RequestBody Map<String, Object> body);

    /** 平台商品下架（status=5） */
    @PostMapping("/platform/{id}/offline")
    Result<Void> platformOffline(@PathVariable("id") Long id, @RequestBody Map<String, String> body);

    /** 平台商品重新上架（status=2；有效期由 item-service 按配置天数续期，可选传 validDays 覆盖） */
    @PostMapping("/platform/{id}/online")
    Result<Void> platformOnline(@PathVariable("id") Long id,
                                @RequestParam(value = "validDays", required = false) Integer validDays);

    /** 平台商品计数概览 */
    @GetMapping("/platform/summary")
    Result<PlatformItemSummaryVO> platformSummary(@RequestParam(value = "campusId", required = false) Long campusId);
}
