package com.ershou.campus.module.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 上架审核参数（ershou.review.*） */
@Data
@ConfigurationProperties(prefix = "ershou.review")
public class ReviewProperties {

    /** 实时推送总开关：关掉后 WebSocket 不再注册，页面自动退回手动刷新 */
    private boolean realtimeEnabled = true;

    /** 审核页心跳有效期：连接/心跳时刷新，超过即视为离线（顶部"3 位审核员在线"用） */
    private Duration onlineTtl = Duration.ofSeconds(90);

    /** 单次批量处置上限，防止前端误传把整库干掉 */
    private int maxBatchSize = 50;

    /**
     * 是否需要卖家二次申请才能上架违规商品。
     *
     * <p>打开（默认）时：违规下架(8) 的商品要卖家主动申请 → 审核员通过 → 才回到在售；
     * 关掉后违规商品保持永久下架（没有二次申请入口）。
     */
    private boolean violationRelistEnabled = true;

    /** 列表里"已处理"记录的保留时长：审核员处置完还能回看自己刚做了什么 */
    private Duration doneKeepWindow = Duration.ofHours(24);
}
