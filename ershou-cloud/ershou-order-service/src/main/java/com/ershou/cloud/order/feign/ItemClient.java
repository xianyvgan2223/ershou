package com.ershou.cloud.order.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 商品服务 OpenFeign 客户端（全量拆分契约）。
 *
 * <p>接口一览：
 * <ul>
 *   <li>{@code GET /inner/items/{id}} → 商品详情（已存在）；</li>
 *   <li>{@code GET /inner/items/batch?ids=1,2} → 批量详情（本轮 item-service 新加，购物车/收藏/列表用）；</li>
 *   <li>{@code PUT /inner/items/{id}/lock?qty=} → 平台款条件扣库存（保留自第 5 轮平台代卖）；</li>
 *   <li>{@code POST /inner/items/{id}/meetup-lock?buyerId=&expireAt=} → 面交锁库（status 2→3，带过期时间）；</li>
 *   <li>{@code POST /inner/items/{id}/meetup-release} → 面交解锁（status 3→2）；</li>
 *   <li>{@code POST /inner/items/{id}/meetup-sold} → 面交成交（status 2/3→4，stock=0）。</li>
 * </ul>
 *
 * <p>所有写接口由 item-service 以本地事务执行；本服务的面交操作方法标了 {@code @GlobalTransactional}，
 * 因此这些写会作为 Seata AT 分支事务注册，本服务本地事务回滚时商品状态/库存由 undo_log 一起回滚。
 * item-service 未就绪时走 {@link ItemClientFallback}（code=90001），业务侧按"降级"处理并记日志。
 */
@FeignClient(name = "ershou-item-service", contextId = "itemClient", path = "/inner/items",
        fallback = ItemClientFallback.class)
public interface ItemClient {

    @GetMapping("/{id}")
    Result<InnerItemVO> get(@PathVariable("id") Long id);

    /** 批量查询：ids 为逗号分隔（如 "17,18,888"） */
    @GetMapping("/batch")
    Result<java.util.List<InnerItemVO>> batch(@RequestParam("ids") String ids);

    /** 平台款条件扣库存（stock 扣减，status 归零置 4），返回 1/0 */
    @PutMapping("/{id}/lock")
    Result<Integer> lock(@PathVariable("id") Long id, @RequestParam("qty") Integer qty);

    /** 面交锁库：status 2→3 + lock_user_id/lock_expire_at；返回 1/0 */
    @PostMapping("/{id}/meetup-lock")
    Result<Integer> meetupLock(@PathVariable("id") Long id,
                               @RequestParam("buyerId") Long buyerId,
                               @RequestParam("expireAt") String expireAt);

    /** 面交解锁：status 3→2 + 清锁；返回 1/0（数字表示受影响行数） */
    @PostMapping("/{id}/meetup-release")
    Result<Integer> meetupRelease(@PathVariable("id") Long id);

    /** 面交成交：status 2/3→4 + stock=0 + 清锁；返回 1/0 */
    @PostMapping("/{id}/meetup-sold")
    Result<Integer> meetupSold(@PathVariable("id") Long id);

    /**
     * 平台单完成收口：按剩余库存决定 status（stock>0→2 否则→4），不动 stock。
     * 由 item-service 实现（第 5 轮平台代卖 v1.1 语义）；未实现时调用方降级仅记 warn。
     */
    @PostMapping("/{id}/platform-settle")
    Result<Integer> platformSettle(@PathVariable("id") Long id);
}
