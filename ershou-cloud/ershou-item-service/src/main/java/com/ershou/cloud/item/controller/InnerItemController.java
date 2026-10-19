package com.ershou.cloud.item.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.service.ItemLockService;
import com.ershou.cloud.item.vo.InnerItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（**不经过网关**，仅供 order-service OpenFeign 调用，设计 4.3）。
 *
 * <p>契约（与 order-member 已对齐）：
 * <ul>
 *   <li>{@code GET /inner/items/{id}} → {@code Result<InnerItemVO>}，不存在时 code=30001；</li>
 *   <li>{@code PUT /inner/items/{id}/lock?qty=1} → {@code Result<Integer>}，data 1 成功 / 0 失败；</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/inner/items")
@RequiredArgsConstructor
public class InnerItemController {

    private static final int CODE_ITEM_NOT_FOUND = 30001;

    private final ItemMapper itemMapper;
    private final ItemLockService itemLockService;

    /** Feign 查询商品快照（下单前校验 status/stock，并取 sellerId/title/cover/price） */
    @GetMapping("/{id}")
    public Result<InnerItemVO> get(@PathVariable Long id) {
        Item item = itemMapper.selectById(id);
        if (item == null) {
            return Result.fail(CODE_ITEM_NOT_FOUND, "商品不存在或已删除");
        }
        return Result.ok(InnerItemVO.from(item));
    }

    /** Feign 锁定库存（条件更新，参与 Seata AT）：1 成功 / 0 失败 */
    @PutMapping("/{id}/lock")
    public Result<Integer> lock(@PathVariable Long id,
                                @RequestParam(defaultValue = "1") Integer qty) {
        int result = itemLockService.lock(id, qty);
        log.info("[inner][lock][{}] qty={} result={}", id, qty, result);
        return Result.ok(result);
    }
}
