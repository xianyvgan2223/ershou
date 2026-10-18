package com.ershou.campus.module.platform.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 平台代卖配置（第 2 轮设计 2.1）。
 *
 * <p>刻意用 {@code @Value} + 默认值而不是新增 application.yml 配置项：
 * 平台代卖是"默认就能用"的功能，部署时不需要改任何配置文件；
 * 需要覆盖时用启动参数 / 环境变量（如 {@code --ershou.platform.seller-id=1001}）即可。
 */
@Getter
@Component
public class PlatformProperties {

    /** 平台官方卖家 sys_user.id（固定 1000，见设计 2.1；该账号不可登录） */
    @Value("${ershou.platform.seller-id:1000}")
    private Long sellerId;

    /** 平台官方卖家昵称（只用于展示兜底；真实昵称以 sys_user 为准） */
    @Value("${ershou.platform.seller-nickname:校园二手官方严选}")
    private String sellerNickname;

    /** 平台商品有效期（天）：管理后台发布后自动下架时间 */
    @Value("${ershou.platform.item-valid-days:30}")
    private int itemValidDays;

    /** 平台单自提窗口（小时）：下单锁库时长，默认 168 小时 = 7 天 */
    @Value("${ershou.platform.lock-hours:168}")
    private int lockHours;
}
