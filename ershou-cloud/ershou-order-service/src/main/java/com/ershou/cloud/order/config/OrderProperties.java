package com.ershou.cloud.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 订单业务参数（Nacos 动态刷新演示点 + 交易状态机常量）。
 *
 * <p>对应 Nacos {@code ershou-order-service.yaml} 的 {@code ershou.order.*}：
 * <pre>
 * ershou:
 *   order:
 *     max-qty: 99              # 单次下单数量上限（@RefreshScope 热生效，演示配置中心）
 *     default-status: 1        # 个人意向单初始状态：1=待卖家确认（与旧单体一致）
 *     lock-buffer-hours: 2     # 卖家接受后锁库到「时间窗结束 + N 小时」
 *     lock-default-hours: 24   # 没填时间窗时锁 N 小时
 *     max-slot-minutes: 180    # 单次面交时间窗上限（3 小时）
 *     max-slot-days: 7         # 最远可预约天数
 *     platform-seller-id: 1000 # 平台代卖官方账号 id
 *     platform-item-status: 2  # 平台单初始状态（2=待自提/待面交）
 * </pre>
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "ershou.order")
public class OrderProperties {

    /** 单次下单数量上限（演示动态配置：Nacos 改动即时生效） */
    private int maxQty = 99;

    /** 个人意向单初始状态：1=待卖家确认（旧单体语义） */
    private int defaultStatus = 1;

    /** 卖家接受后锁库到「时间窗结束 + N 小时」 */
    private int lockBufferHours = 2;

    /** 没填时间窗时锁定 N 小时 */
    private int lockDefaultHours = 24;

    /** 单次面交时间窗上限（分钟） */
    private int maxSlotMinutes = 180;

    /** 最远可预约天数 */
    private int maxSlotDays = 7;

    /** 平台官方账号 id（平台代卖单卖家；不可登录、不参与信用分） */
    private Long platformSellerId = 1000L;

    /** 平台自提单初始状态：2=待自提/待面交 */
    private int platformItemStatus = 2;
}
