package com.ershou.cloud.item.search;

import java.util.List;
import java.util.Map;

/**
 * ES 检索结果：命中商品 id（已按 ES 排序）、命中总数、关键词高亮片段。
 */
public record ItemSearchHits(List<Long> ids, long total, Map<Long, Highlight> highlights) {

    /** 高亮片段：ES 侧已做 HTML 转义，只会多出 <em> 标签 */
    public record Highlight(String title, String description) {
    }
}
