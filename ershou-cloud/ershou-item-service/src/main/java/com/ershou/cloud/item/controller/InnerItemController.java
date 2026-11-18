package com.ershou.cloud.item.controller;

import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.item.dto.ItemPublishDTO;
import com.ershou.cloud.item.dto.ItemUpdateDTO;
import com.ershou.cloud.item.service.ItemInnerService;
import com.ershou.cloud.item.service.ItemLockService;
import com.ershou.cloud.item.vo.InnerItemVO;
import com.ershou.cloud.item.vo.ItemVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 内部商品接口（**不经过网关**，仅供其他微服务 OpenFeign 调用）。
 *
 * <p>接口清单（契约 {@code ershou-cloud/docs/full-split-contract.md} §三 + T102 追加）：
 * <ul>
 *   <li>通用：{@code GET /{id}} 详情、{@code GET /batch?ids=} 批量、{@code GET /stats} 统计；</li>
 *   <li>map-service：{@code GET /by-point}、{@code GET /count-by-point}；</li>
 *   <li>order-service：{@code PUT /{id}/lock?qty=}（平台款扣库存）、
 *       {@code POST /{id}/meetup-lock}、{@code POST /{id}/meetup-release}、{@code POST /{id}/meetup-sold}；</li>
 *   <li>admin-service：{@code POST /{id}/status?status=}（审核/强制下架）；</li>
 *   <li>platform-service：{@code /platform} 一组（列表/详情/创建/编辑/上下架/概览）。</li>
 * </ul>
 * 内部接口由各服务直连（lb://ershou-item-service），鉴权不在本服务（网关统一鉴权后注入 X-User-Id）。
 */
@Slf4j
@RestController
@RequestMapping("/inner/items")
@RequiredArgsConstructor
public class InnerItemController {

    private static final int CODE_ITEM_NOT_FOUND = 30001;

    private final ItemInnerService itemInnerService;
    private final ItemLockService itemLockService;

    // ------------------------------------------------------------------ 通用

    /** 商品详情（含 T102 追加字段 preferPointId/campusId/isUnique） */
    @GetMapping("/{id}")
    public Result<InnerItemVO> get(@PathVariable Long id) {
        return Result.ok(itemInnerService.detail(id));
    }

    /** 批量详情：ids=1,2,3（顺带解决调用方 N+1；不存在的 id 跳过） */
    @GetMapping("/batch")
    public Result<List<InnerItemVO>> batch(@RequestParam("ids") String ids) {
        List<Long> idList = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).toList();
        return Result.ok(itemInnerService.batch(idList));
    }

    /** 商品统计：{onSale,pendingReview,offline,total} */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(itemInnerService.stats());
    }

    /**
     * 管理端商品分页（admin-service「商品管理」页，Lead T102 最终补充）：
     * status/keyword/sellerId 可选；返回 InnerItemVO（management 需要的字段齐全）。
     */
    @GetMapping
    public Result<PageResult<InnerItemVO>> adminPage(@RequestParam(required = false) Integer status,
                                                     @RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Long sellerId,
                                                     @RequestParam(defaultValue = "1") long page,
                                                     @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(itemInnerService.adminPage(status, keyword, sellerId, page, pageSize));
    }

    /** 某卖家的在售商品数（T102 追加：user-service 公开主页统计用）→ {sellerId,count} */
    @GetMapping("/count-by-seller")
    public Result<Map<String, Object>> countBySeller(@RequestParam Long sellerId) {
        return Result.ok(itemInnerService.countBySeller(sellerId));
    }

    /** 点位商品分页（map-service 地图小窗） */
    @GetMapping("/by-point")
    public Result<PageResult<InnerItemVO>> byPoint(@RequestParam(required = false) Long campusId,
                                                   @RequestParam(required = false) Long pointId,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(itemInnerService.byPoint(campusId, pointId, page, pageSize));
    }

    /** 校区内各点位在售数（map-service 气泡数字）：[{pointId,count}] */
    @GetMapping("/count-by-point")
    public Result<List<Map<String, Object>>> countByPoint(@RequestParam(required = false) Long campusId) {
        return Result.ok(itemInnerService.countByPoint(campusId));
    }

    /** 管理端状态处置（2 审核通过 / 6 驳回 / 5 下架 / 8 违规强制下架）；rejectCode 仅驳回时使用 */
    @PostMapping("/{id}/status")
    public Result<Integer> updateStatus(@PathVariable Long id,
                                        @RequestParam Integer status,
                                        @RequestParam(required = false) String reason,
                                        @RequestParam(required = false) String rejectCode) {
        Long operatorId = com.ershou.cloud.common.web.UserContext.getUserId();
        return Result.ok(itemInnerService.updateStatus(id, status, reason, rejectCode, operatorId));
    }

    // ------------------------------------------------------------------ order-service：库存/面交状态机

    /** 平台款/秒杀条件扣库存：data=1 成功 / 0 失败（参与 Seata AT） */
    @PutMapping("/{id}/lock")
    public Result<Integer> lock(@PathVariable Long id,
                                @RequestParam(defaultValue = "1") Integer qty) {
        int result = itemLockService.lock(id, qty);
        log.info("[inner][lock][{}] qty={} result={}", id, qty, result);
        return Result.ok(result);
    }

    /** 面交锁库：status 2→3，写 buyerId/lockExpireAt；data=1/0（参与 Seata AT） */
    @PostMapping("/{id}/meetup-lock")
    public Result<Integer> meetupLock(@PathVariable Long id,
                                      @RequestParam Long buyerId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                      LocalDateTime expireAt) {
        return Result.ok(itemLockService.meetupLock(id, buyerId, expireAt));
    }

    /** 面交释放锁：status 3→2；data=1/0 */
    @PostMapping("/{id}/meetup-release")
    public Result<Integer> meetupRelease(@PathVariable Long id) {
        return Result.ok(itemLockService.meetupRelease(id));
    }

    /** 面交成交：status IN (2,3)→4、stock=0、清锁；data=1/0 */
    @PostMapping("/{id}/meetup-sold")
    public Result<Integer> meetupSold(@PathVariable Long id) {
        return Result.ok(itemLockService.meetupSold(id));
    }

    // ------------------------------------------------------------------ platform-service：平台商品

    /** 平台商品分页（seller_type=2；status 为空=全部；T106 支持 categoryId 筛选 + latest/price_asc/price_desc 排序） */
    @GetMapping("/platform")
    public Result<PageResult<ItemVO>> platformPage(@RequestParam(required = false) Long campusId,
                                                   @RequestParam(required = false) Long pointId,
                                                   @RequestParam(required = false) Integer status,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Long categoryId,
                                                   @RequestParam(defaultValue = "latest") String sort,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "12") long pageSize) {
        return Result.ok(itemInnerService.platformPage(campusId, pointId, status, keyword,
                categoryId, sort, page, pageSize));
    }

    /** 平台商品概览：{platformItemCount,onSaleCount,totalStock,soldCount,gmv(需订单域),note} */
    @GetMapping("/platform/summary")
    public Result<Map<String, Object>> platformSummary(@RequestParam(required = false) Long campusId) {
        return Result.ok(itemInnerService.platformSummary(campusId));
    }

    /** 平台商品详情 */
    @GetMapping("/platform/{id}")
    public Result<ItemVO> platformDetail(@PathVariable Long id) {
        return Result.ok(itemInnerService.platformDetail(id));
    }

    /** 平台商品编辑（内部调用，不做卖家属主校验但校验 seller_type=2） */
    @PutMapping("/platform/{id}")
    public Result<ItemVO> platformUpdate(@PathVariable Long id,
                                         @Valid @RequestBody ItemUpdateDTO dto) {
        return Result.ok(itemInnerService.platformUpdate(id, dto));
    }

    /** 创建平台商品（seller_type=2、platform_point_id 必填、免审核直接在售） */
    @PostMapping("/platform")
    public Result<Map<String, Object>> platformCreate(@Valid @RequestBody ItemPublishDTO dto) {
        Long operatorId = com.ershou.cloud.common.web.UserContext.getUserId();
        return Result.ok(itemInnerService.platformCreate(dto, operatorId));
    }

    /** 平台商品下架：body {reason?} */
    @PostMapping("/platform/{id}/offline")
    public Result<Void> platformOffline(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        itemInnerService.platformOffline(id, reason);
        return Result.ok();
    }

    /** 平台商品重新上架：5/7 → 2 */
    @PostMapping("/platform/{id}/online")
    public Result<Void> platformOnline(@PathVariable Long id) {
        itemInnerService.platformOnline(id);
        return Result.ok();
    }
}
