package com.ershou.cloud.user.feign;

import com.ershou.cloud.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * item-service 公开商品列表的 Feign 客户端（**只用于公开主页的"在售数"**）。
 *
 * <h3>为什么调用公开接口而不是 /inner 接口</h3>
 * 契约第三节的 item-service 内部接口没有"按卖家计数"：
 * {@code /inner/items/{id}}、{@code /batch}、{@code /by-point}、{@code /count-by-point}、
 * {@code /{id}/status}、{@code /platform}、{@code /{id}/lock} 都拿不到"某卖家在售件数"。
 * 公开主页的 {@code onSaleCount} 又必须保留（Web 用户主页会显示"在售 N 件"），
 * 所以这里退化调用 item-service 公开列表 {@code GET /api/v1/items?sellerId=&pageNum=1&pageSize=1}
 * 取分页 total。
 *
 * <p><b>已知限制</b>：若 item-service 的公开列表还没支持 {@code sellerId} 参数（参数被忽略），
 * 返回的 total 会是全站商品数 —— 调用方 {@code UserService#countOnSale} 会用返回记录的
 * {@code seller.id} 做防御式校验，不匹配时降级为 0，避免把错误数字展示给用户。
 * 建议后续 item-service 增补 {@code GET /inner/items/count-by-seller} 后替换本调用。
 *
 * <p>本客户端**不配 Sentinel fallback**：调用失败（商品服务未启动/超时）时由调用方 try/catch
 * 降级为 0；这是展示字段，不应该让整个公开主页失败。
 */
@FeignClient(name = "ershou-item-service", contextId = "userItemListClient", path = "/api/v1")
public interface ItemServiceClient {

    /**
     * 商品列表（公开读）。
     *
     * @param sellerId 卖家 id（item-service 支持后生效；不支持时会被忽略，调用方做防御校验）
     * @param pageNum  页码（1 开始）
     * @param pageSize 每页条数
     */
    @GetMapping("/items")
    Result<ItemPageVO> page(@RequestParam("sellerId") Long sellerId,
                            @RequestParam("pageNum") long pageNum,
                            @RequestParam("pageSize") long pageSize);
}
