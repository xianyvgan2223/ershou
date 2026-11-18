package com.ershou.cloud.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建面交单请求（与旧单体 {@code module.order.dto.CreateOrderDTO} 的 JSON 完全兼容）。
 *
 * <p>旧单体前端（商品详情「约面交」弹窗）实际只传 itemId/quantity/remark；pointId/timeSlotStart/End
 * 是历史字段：服务端**忽略**（面交地点与时间由卖家定，买家只提意向），保留字段只为兼容旧客户端/脚本。
 *
 * <p>{@code debugFail} 为第 5 轮 Seata 演示开关，保留但默认 false（生产/迁移期不触发）。
 */
@Data
public class CreateOrderDTO {

    @NotNull(message = "缺少商品 id")
    private Long itemId;

    /** 预约数量，缺省 1（旧单体语义：>=1，未做库存上限校验） */
    private Integer quantity = 1;

    /** 历史字段：买家传入的点位被忽略（按商品自己的 preferPointId） */
    private Long pointId;

    /** 历史字段：买家传入的时间窗被忽略（留空=待协商） */
    private String timeSlotStart;

    private String timeSlotEnd;

    private String remark;

    /** 仅演示用：true = 建单后抛异常触发 Seata 全局回滚（默认关闭） */
    private Boolean debugFail = false;
}
