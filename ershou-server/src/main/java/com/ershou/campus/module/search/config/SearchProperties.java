package com.ershou.campus.module.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 商品搜索（Elasticsearch）相关配置，对应 application-*.yml 的 ershou.search.*
 */
@Data
@ConfigurationProperties(prefix = "ershou.search")
public class SearchProperties {

    /** 总开关：false = 完全退回 MySQL LIKE */
    private boolean enabled = true;

    /** 索引名 */
    private String indexName = "ershou_item";

    /** ES 不可用时是否自动降级 MySQL LIKE（false = 直接报系统繁忙） */
    private boolean fallbackToDb = true;

    /** 启动时索引不存在或为空则全量重建 */
    private boolean reindexOnStartup = true;

    /** 增量同步 cron（兜底：任何漏掉的写路径都会被补齐） */
    private String syncCron = "0 */2 * * * ?";

    /** 增量同步回看窗口（分钟），覆盖停机期间的空档 */
    private int syncLookbackMinutes = 30;

    /** 单批写入条数 */
    private int bulkSize = 200;

    /** ES 请求失败后的熔断时长（毫秒），窗口内直接走降级路径 */
    private long failureBackoffMs = 15000;
}
