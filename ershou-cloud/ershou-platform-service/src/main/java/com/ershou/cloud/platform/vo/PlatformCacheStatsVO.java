package com.ershou.cloud.platform.vo;

import lombok.Data;

import java.util.List;

/**
 * 运营台 · Redis 缓存统计（GET /platform/admin/cache/stats，只读扫描）。
 * 字段与 Web `api/platform-console.ts#ConsoleCacheStatsVO` 对齐。
 */
@Data
public class PlatformCacheStatsVO {
    /** 预热分布式锁当前是否被持有 */
    private Boolean warmupLockHeld;
    private Integer totalKeys;
    private List<CacheKeyItem> keys;

    /** 单个 ershou:platform:* key 的快照 */
    @Data
    public static class CacheKeyItem {
        private String key;
        /** Redis 类型：string / hash / …（小写类型名） */
        private String type;
        /** 剩余 TTL（秒，-1=永不过期，-2=不存在） */
        private Long ttl;
        /** String 给值（库存/JSON）；Hash 给 field 数量（size）；其它类型给 null */
        private Object value;
    }
}
