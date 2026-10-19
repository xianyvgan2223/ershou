package com.ershou.cloud.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建面交单请求（设计 4.4：body {@code {itemId, quantity, remark, debugFail}}）。
 *
 * <p>{@code debugFail} 只用于 Seata 演示：传 true 时服务端在"扣库存 + 插单"之后主动抛异常，
 * 用于验证全局回滚（item 库存由 Seata AT 的 undo_log 回滚，订单不落库）。
 */
@Data
public class CreateOrderDTO {

    @NotNull(message = "缺少商品 id")
    private Long itemId;

    /** 购买数量，缺省 1（云切片演示单件下单） */
    private Integer quantity = 1;

    private String remark;

    /** 仅演示用：true = 建单成功后抛异常，触发 Seata 全局回滚 */
    private Boolean debugFail = false;
}
