package com.ershou.cloud.item.search;

import com.ershou.cloud.item.dto.ItemQueryDTO;
import com.ershou.cloud.item.search.SearchProperties;
import com.ershou.cloud.item.search.ItemSearchHits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品关键词检索（Elasticsearch）。
 *
 * <p>只负责产出"命中 id + 总数 + 高亮"，商品字段仍回 MySQL 取最新值，避免索引与库字段漂移。
 * <b>平台代卖标注（sellerType / platformPointId）也走这条回库路径</b>：本类不产出 VO 字段，
 * 由 {@code ItemService.searchViaEs} 回库后交给 {@code ItemService#toVOList} 统一组装，
 * 所以关键词搜索与普通列表的标注口径完全一致（索引里的 sellerType 只作为文档留存，不参与检索）。
 * 查询走底层 RestClient + 手写 JSON DSL：DSL 由服务端语义校验，
 * 不受 ES Java 客户端强类型模型（例如 sort 不接受 \"desc" 简写）的版本约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemSearchService {

    /** ES 默认 max_result_window：超出的深分页交回 MySQL */
    private static final int MAX_RESULT_WINDOW = 10000;

    private final RestClient restClient;
    private final EsGateway es;
    private final SearchProperties props;
    private final ObjectMapper json;

    /**
     * 是否走 ES：开关打开 + 有关键词 + ES 健康 + **没有按点位筛选**。
     *
     * <p>为什么要排掉 preferPointId：ES 索引文档（ItemDocument）里**没有这个字段**，
     * 硬走 ES 会把这个过滤条件丢掉（返回该点位之外的商品，属于静默错误）。
     * 点位查询本来就只有几条、走 MySQL 精确又便宜，没必要为了它改索引映射。
     */
    public boolean supports(ItemQueryDTO query) {
        return props.isEnabled()
                && StringUtils.hasText(query.getKeyword())
                && query.getPreferPointId() == null
                && es.healthy();
    }

    public void markFailure(Exception e) {
        es.markFailure("search", e);
    }

    public ItemSearchHits search(ItemQueryDTO query) {
        if (es.client() == null) {
            throw new IllegalStateException("Elasticsearch 当前不可用");
        }
        int pageNum = (int) Math.max(query.getPageNum(), 1);
        int pageSize = (int) Math.min(Math.max(query.getPageSize(), 1), 100);
        int from = (pageNum - 1) * pageSize;
        if (from + pageSize > MAX_RESULT_WINDOW) {
            // 不是 ES 故障，只是这次查询不适合走 ES，直接回落 MySQL，不计入熔断
            throw new NotSuitableForEsException("超出 ES 深分页窗口 " + MAX_RESULT_WINDOW);
        }
        String body = queryJson(query, from, pageSize);
        log.debug("[search][es] DSL {}", body);
        try {
            Request request = new Request("POST", "/" + props.getIndexName() + "/_search");
            request.setJsonEntity(body);
            Response response = restClient.performRequest(request);
            JsonNode root = json.readTree(response.getEntity().getContent());
            JsonNode hits = root.path("hits");
            JsonNode totalNode = hits.path("total");
            long total = totalNode.has("value") ? totalNode.path("value").asLong() : hits.path("hits").size();

            List<Long> ids = new ArrayList<>();
            Map<Long, ItemSearchHits.Highlight> highlights = new LinkedHashMap<>();
            for (JsonNode hit : hits.path("hits")) {
                if (!hit.hasNonNull("_id")) {
                    continue;
                }
                long id = Long.parseLong(hit.path("_id").asText());
                ids.add(id);
                JsonNode highlight = hit.path("highlight");
                if (highlight.isObject() && !highlight.isEmpty()) {
                    highlights.put(id, new ItemSearchHits.Highlight(
                            firstFragment(highlight, "title"), firstFragment(highlight, "description")));
                }
            }
            es.markSuccess();
            return new ItemSearchHits(ids, total, highlights);
        } catch (Exception e) {
            throw new IllegalStateException("ES 检索失败: " + e.getMessage(), e);
        }
    }

    /** 拼检索 DSL：status=2 恒定过滤 + 关键词 multi_match + 类目/校区/卖家过滤 + 排序 + 高亮 */
    private String queryJson(ItemQueryDTO query, int from, int size) {
        List<String> filters = new ArrayList<>();
        // ⚠️ status=2 这个过滤**必须同时进 DSL**，不能只在回库后筛。
        // 曾经只有回库筛（ItemService.searchViaEs 里那段），结果：
        // ES 的 total 把已下架/已售出的也算进去，而实际返回的 records 被筛掉 →
        // 用户看到"共 12 件"却一件都列不出来（搜「自行车」实测复现）。
        filters.add("{\"term\":{\"status\":2}}");
        if (query.getCategoryId() != null) {
            filters.add("{\"term\":{\"categoryId\":" + query.getCategoryId() + "}}");
        }
        if (query.getCampusId() != null) {
            filters.add("{\"term\":{\"campusId\":" + query.getCampusId() + "}}");
        }
        if (query.getSellerId() != null) {
            filters.add("{\"term\":{\"sellerId\":" + query.getSellerId() + "}}");
        }
        boolean hasKeyword = StringUtils.hasText(query.getKeyword());
        String must = "";
        if (hasKeyword) {
            must = "\"must\":[{\"multi_match\":{\"query\":" + quote(query.getKeyword())
                    + ",\"fields\":[\"title^3\",\"categoryName^2\",\"description\"],"
                    + "\"type\":\"best_fields\",\"operator\":\"or\"}}],";
        }
        return """
                {
                  "from": %d,
                  "size": %d,
                  "track_total_hits": true,
                  "_source": false,
                  "query": { "bool": { %s "filter": [%s] } },
                  "sort": %s,
                  "highlight": {
                    "pre_tags": ["<em>"],
                    "post_tags": ["</em>"],
                    "encoder": "html",
                    "fields": {
                      "title": { "number_of_fragments": 0 },
                      "description": { "fragment_size": 80, "number_of_fragments": 1 }
                    }
                  }
                }
                """.formatted(from, size, must, String.join(",", filters), sortJson(query.getSort(), hasKeyword));
    }

    private String sortJson(String sort, boolean hasKeyword) {
        String tail = "{\"publishedAt\":\"desc\"},{\"id\":\"desc\"}";
        return switch (sort == null ? "latest" : sort) {
            case "price_asc" -> "[{\"price\":\"asc\"}," + tail + "]";
            case "price_desc" -> "[{\"price\":\"desc\"}," + tail + "]";
            default -> hasKeyword ? "[{\"_score\":\"desc\"}," + tail + "]" : "[" + tail + "]";
        };
    }

    private String quote(String value) {
        try {
            return json.writeValueAsString(value == null ? "" : value.trim());
        } catch (Exception e) {
            throw new IllegalStateException("关键词序列化失败", e);
        }
    }

    private static String firstFragment(JsonNode highlight, String field) {
        JsonNode node = highlight.path(field);
        return node.isArray() && !node.isEmpty() ? node.get(0).asText() : null;
    }

    /** 该查询不适合走 ES（例如深分页超出 max_result_window），调用方应直接回落 MySQL */
    public static class NotSuitableForEsException extends RuntimeException {
        public NotSuitableForEsException(String message) {
            super(message);
        }
    }
}
