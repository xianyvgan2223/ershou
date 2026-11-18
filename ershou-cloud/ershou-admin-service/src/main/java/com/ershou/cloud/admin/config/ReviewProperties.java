package com.ershou.cloud.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 上架审核参数（Nacos {@code ershou.review.*}，与单体同名键，便于文档/配置复用） */
@Data
@ConfigurationProperties(prefix = "ershou.review")
public class ReviewProperties {

    /** 实时推送总开关（首版 WS 只做握手/ping-pong；广播事件由 item-service 侧产生，见遗留） */
    private boolean realtimeEnabled = true;

    /** 审核页在线心跳有效期（顶部"几位审核员在线"） */
    private Duration onlineTtl = Duration.ofSeconds(90);

    /** 单次批量处置上限，防止误传把整库干掉 */
    private int maxBatchSize = 50;
}
