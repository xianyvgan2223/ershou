package com.ershou.cloud.item.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.item.support.RedisKeys;
import com.ershou.cloud.item.entity.Item;
import com.ershou.cloud.item.entity.ItemCategory;
import com.ershou.cloud.item.mapper.ItemCategoryMapper;
import com.ershou.cloud.item.mapper.ItemMapper;
import com.ershou.cloud.item.search.SearchProperties;
import com.ershou.cloud.item.search.ItemDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 商品搜索索引的维护：建索引、写文档、全量重建、增量兜底同步。
 *
 * <p>索引里保存**所有状态**的商品，公开检索固定过滤 status=2；
 * 这样状态流转（锁库 3 / 售出 4 / 下架 5 / 过期 7）只需 upsert，不需要删文档。
 *
 * <p>索引一致性三层保障：业务写路径 afterCommit 同步 + 每 2 分钟按 updated_at 增量兜底 + 管理员可手动重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemIndexService {

    private final EsGateway es;
    private final RestClient restClient;
    private final SearchProperties props;
    private final ItemMapper itemMapper;
    private final ItemCategoryMapper categoryMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    /** 重建/同步串行执行，避免并发把 ES 打满 */
    private final ExecutorService indexExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "es-index-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean reindexing = new AtomicBoolean(false);

    // ------------------------------------------------------------------ 启动

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.isEnabled()) {
            log.info("[search][es] ershou.search.enabled=false，搜索走 MySQL LIKE");
            return;
        }
        indexExecutor.execute(() -> {
            try {
                ElasticsearchClient client = es.client();
                if (client == null) {
                    log.warn("[search][es] 启动时 ES 不可达，等定时同步重试；搜索暂时降级 MySQL");
                    return;
                }
                boolean exists = indexExists(client, props.getIndexName());
                String analyzer = ensureIndex();
                long count = client.count(c -> c.index(props.getIndexName())).count();
                if (!exists || count == 0) {
                    if (props.isReindexOnStartup()) {
                        log.info("[search][es] 索引 {} 为空(analyzer={})，开始全量重建", props.getIndexName(), analyzer);
                        reindexAll();
                    }
                } else {
                    syncIncremental();
                }
            } catch (Exception e) {
                log.warn("[search][es] 启动初始化失败：{}", e.getMessage());
            }
        });
    }

    /** 兜底同步：任何漏掉的写路径都会在这里补齐（多实例部署需换 ShedLock，见开发文档 7.10） */
    @Scheduled(cron = "${ershou.search.sync-cron:0 */2 * * * ?}")
    public void syncIncremental() {
        if (es.client() == null) {
            return;
        }
        int batch = Math.max(props.getBulkSize(), 1);
        try {
            LocalDateTime since = LocalDateTime.ofInstant(Instant.ofEpochMilli(watermark()), ZoneId.systemDefault());
            List<Item> changed = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                    .gt(Item::getUpdatedAt, since)
                    .orderByAsc(Item::getUpdatedAt)
                    .last("LIMIT " + batch));
            if (changed.isEmpty()) {
                return;
            }
            int written = saveAll(changed);
            LocalDateTime max = changed.get(changed.size() - 1).getUpdatedAt();
            boolean capped = changed.size() >= batch;
            long next = max.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - (capped ? 1000L : 0L);
            setWatermark(next);
            log.info("[search][es] 增量同步 {} 件（写入 {}，水位 {}）", changed.size(), written, Instant.ofEpochMilli(next));
        } catch (Exception e) {
            es.markFailure("incremental-sync", e);
        }
    }

    // -------------------------------------------------------------- 索引结构

    /** 建索引候选方案：分词器 + 是否挂查询期同义词过滤器，按"从好到差"顺序尝试 */
    private record AnalyzerProfile(String tokenizer, boolean synonyms) {
        String label() {
            String name = tokenizer == null ? "ngram" : tokenizer;
            return synonyms ? name + "+syn" : name;
        }
    }

    private static final List<AnalyzerProfile> PROFILES = List.of(
            new AnalyzerProfile("ik_max_word", true),   // 默认镜像：IK + 同义词
            new AnalyzerProfile("ik_max_word", false),  // 只有 IK（无同义词表）
            new AnalyzerProfile("smartcn", true),       // 官方中文插件 + 同义词
            new AnalyzerProfile("smartcn", false),
            new AnalyzerProfile(null, false));          // 内置 ngram 兜底

    /** 建索引（幂等）。返回实际生效的方案，如 ik_max_word+syn / ngram */
    public String ensureIndex() {
        ElasticsearchClient client = es.client();
        if (client == null) {
            throw new IllegalStateException("Elasticsearch 当前不可用");
        }
        String index = props.getIndexName();
        if (indexExists(client, index)) {
            if (indexUsable(index)) {
                return "existing";
            }
            // 常见于：索引被删后又被动写入自动创建（mapping 是动态的）、或换过分词插件，
            // 此时按当前环境删掉重建，数据从 MySQL 重放，不会丢。
            log.warn("[search][es] 索引 {} 与当前约定不符，删除后按当前可用方案重建", index);
            try {
                client.indices().delete(d -> d.index(index));
            } catch (Exception e) {
                throw new IllegalStateException("索引 " + index + " 删除失败: " + e.getMessage(), e);
            }
        }
        Exception last = null;
        for (AnalyzerProfile profile : PROFILES) {
            try {
                client.indices().create(c -> c.index(index).withJson(new StringReader(mappingJson(profile))));
                log.info("[search][es] 创建索引 {} 成功，方案 = {}", index, profile.label());
                return profile.label();
            } catch (Exception e) {
                last = e;
                log.info("[search][es] 方案 {} 不可用，试下一个：{}", profile.label(), e.getMessage());
            }
        }
        throw new IllegalStateException("索引 " + index + " 创建失败: "
                + (last == null ? "未知原因" : last.getMessage()), last);
    }

    /**
     * 索引 settings + mappings。
     * 中文分词链：索引期用细粒度（ik_max_word），检索期用粗粒度（ik_smart）；
     * 查询期再挂 synonym_graph，把「高数」展开成「高数/高等数学」，实现简称也能搜到全称（反之亦然）。
     */
    private String mappingJson(AnalyzerProfile profile) {
        boolean plugin = profile.tokenizer() != null;
        String indexTokenizer = plugin ? profile.tokenizer() : "ershou_ngram";
        String searchTokenizer = switch (profile.tokenizer() == null ? "" : profile.tokenizer()) {
            case "ik_max_word" -> "ik_smart";
            case "smartcn" -> "smartcn";
            default -> "ershou_ngram";
        };
        String plainFilters = plugin ? "" : ", \"filter\": [\"lowercase\"]";
        // synonym_graph 只能跑在查询期（ES 会拒绝把同义词挂到索引分析器），所以只给 search analyzer
        String indexFilters = plainFilters;
        String searchFilters = profile.synonyms() ? ", \"filter\": [\"ershou_syn\"]" : plainFilters;

        StringBuilder builder = new StringBuilder();
        if (!plugin) {
            builder.append("""
                    "tokenizer": {
                      "ershou_ngram": { "type": "ngram", "min_gram": 1, "max_gram": 2, "token_chars": ["letter", "digit"] }
                    },
                    """);
        }
        if (profile.synonyms()) {
            builder.append("""
                    "filter": {
                      "ershou_syn": { "type": "synonym_graph", "synonyms_path": "analysis-ik/synonyms.txt",
                                      "updateable": true, "lenient": true }
                    },
                    """);
        }
        builder.append("""
                  "analyzer": {
                    "ershou_index":  { "type": "custom", "tokenizer": "%s"%s },
                    "ershou_search": { "type": "custom", "tokenizer": "%s"%s }
                  }""".formatted(indexTokenizer, indexFilters, searchTokenizer, searchFilters));
        String analysis = builder.toString();
        return """
                {
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                    "analysis": { %s }
                  },
                  "mappings": {
                    "properties": {
                      "id":             { "type": "long" },
                      "sellerId":       { "type": "long" },
                      "schoolId":       { "type": "long" },
                      "campusId":       { "type": "long" },
                      "categoryId":     { "type": "long" },
                      "categoryName":   { "type": "text", "analyzer": "ershou_index", "search_analyzer": "ershou_search" },
                      "title":          { "type": "text", "analyzer": "ershou_index", "search_analyzer": "ershou_search",
                                          "fields": { "raw": { "type": "keyword", "ignore_above": 256 } } },
                      "description":    { "type": "text", "analyzer": "ershou_index", "search_analyzer": "ershou_search" },
                      "price":          { "type": "double" },
                      "originalPrice":  { "type": "double" },
                      "conditionLevel": { "type": "integer" },
                      "negotiable":     { "type": "integer" },
                      "stock":          { "type": "integer" },
                      "isUnique":       { "type": "integer" },
                      "status":         { "type": "integer" },
                      "sellerType":     { "type": "integer" },
                      "coverUrl":       { "type": "keyword", "index": false },
                      "publishedAt":    { "type": "long" },
                      "updatedAt":      { "type": "long" },
                      "viewCount":      { "type": "integer" },
                      "favCount":       { "type": "integer" }
                    }
                  }
                }
                """.formatted(analysis);
    }

    // ------------------------------------------------------------ 全量重建

    /**
     * 全量重建（同步执行，供定时/内部调用）。
     *
     * <p><b>它是"清空重建"，不是"增量覆盖"</b>：先删掉整个索引再按 MySQL 重建。
     *
     * <p>为什么必须这样：这个方法以前只做 upsert（逐条覆盖），**永远不删除多余文档**。
     * 于是任何"绕过写路径"的数据变化都会在索引里留下幽灵文档 ——
     * 最典型的是直接用 SQL 删商品（测试脚本经常这么干），ES 根本不知道它们没了。
     * 实测累积到 {@code ES 311 条 vs MySQL 在售 10 条}，后果是搜索结果 total 虚高：
     * 搜「自行车」显示"共 12 件"却一条都列不出来（total 来自 ES，records 回库后被 status 过滤掉）。
     * 管理员点「重新索引」时得到"重建成功"的提示，但幽灵文档一条没少 —— 这个按钮名不副实。
     */
    public Map<String, Object> reindexAll() {
        ElasticsearchClient client = es.client();
        if (client == null) {
            throw new IllegalStateException("Elasticsearch 当前不可用：" + es.lastError());
        }
        long startedAt = System.currentTimeMillis();
        // 先删索引：不删的话下面全是 upsert，多的文档留着不走。
        // 全程数据源是 MySQL，删了重建不会丢东西（重建期间搜索会短暂降级到 MySQL LIKE）。
        String index = props.getIndexName();
        if (indexExists(client, index)) {
            try {
                client.indices().delete(d -> d.index(index));
                log.info("[search][es] 已删除旧索引 {}，准备按 MySQL 全量重建", index);
            } catch (Exception e) {
                throw new IllegalStateException("索引 " + index + " 删除失败（重建已中止）: " + e.getMessage(), e);
            }
        }
        String analyzer = ensureIndex();
        int batch = Math.max(props.getBulkSize(), 1);
        long indexed = 0;
        long lastId = 0L;
        while (true) {
            List<Item> items = itemMapper.selectList(Wrappers.<Item>lambdaQuery()
                    .gt(Item::getId, lastId)
                    .orderByAsc(Item::getId)
                    .last("LIMIT " + batch));
            if (items.isEmpty()) {
                break;
            }
            indexed += saveAll(items);
            lastId = items.get(items.size() - 1).getId();
            if (items.size() < batch) {
                break;
            }
        }
        // 水位回拨：重建期间的增量变更下轮再同步一遍（upsert 幂等）
        setWatermark(startedAt - 60_000L);
        // 显式 refresh：bulk 写入后索引默认要等约 1 秒才可见。
        // 不刷新的话，"重建完成"返回时 count / 搜索还是旧值（实测刚返回时 _count = 0），
        // 管理员点完重建看到 docCount=0 会以为重建失败 —— 其实是还没刷新。
        long docCount = refreshAndCount();
        long visible = refreshAndCountOnSale();
        log.info("[search][es] 全量重建完成：{} 件（analyzer={}，索引文档 {} 条，其中在售可见 {} 条）",
                indexed, analyzer, docCount, visible);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexName", props.getIndexName());
        result.put("analyzer", analyzer);
        result.put("indexed", indexed);
        result.put("visible", visible);
        result.put("docCount", docCount);
        result.put("rebuilt", true); // 标记这次是清空重建（调用方可据此判断幽灵文档已清掉）
        return result;
    }

    /** 强制刷新并返回当前可见文档数（重建/清理后要立刻断言一致性时用） */
    private long refreshAndCount() {
        ElasticsearchClient client = es.client();
        if (client == null) {
            return -1;
        }
        try {
            client.indices().refresh(r -> r.index(props.getIndexName()));
        } catch (Exception e) {
            log.warn("[search][es] 刷新索引失败（不影响数据，只是可见性延迟）：{}", e.getMessage());
        }
        try {
            return client.count(c -> c.index(props.getIndexName())).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 刷新后统计**在售**（status=2）文档数 —— 也就是公开检索实际能看到多少条。
     *
     * <p>为什么必须和 {@link #refreshAndCount()} 分开：索引里存**所有状态**的商品
     * （设计如此：状态流转只 upsert、不删文档，公开检索固定过滤 status=2），
     * 所以"索引文档数"天然大于"用户能看到几件"。
     *
     * <p>2026-10-10 踩过：接口只报 total，重建完显示 12 条、搜索却只有 10 条，
     * 看着像丢了数据 —— 其实 2 条是已过期的。管理员判断"重建有没有生效"该看这个数，
     * 它和搜索页的 total 完全一致。
     */
    private long refreshAndCountOnSale() {
        ElasticsearchClient client = es.client();
        if (client == null) {
            return -1;
        }
        try {
            return client.count(c -> c.index(props.getIndexName())
                    .query(q -> q.term(t -> t.field("status").value(2)))).count();
        } catch (Exception e) {
            log.warn("[search][es] 统计在售文档数失败：{}", e.getMessage());
            return -1;
        }
    }

    /** 后台异步重建，立即返回 */
    public Map<String, Object> reindexAllAsync() {
        if (!reindexing.compareAndSet(false, true)) {
            return Map.of("started", false, "message", "已有重建任务在执行中");
        }
        indexExecutor.execute(() -> {
            try {
                reindexAll();
            } catch (Exception e) {
                es.markFailure("reindex", e);
            } finally {
                reindexing.set(false);
            }
        });
        return Map.of("started", true, "message", "索引重建已在后台开始，可用 /api/v1/search/status 查看结果");
    }

    // ------------------------------------------------------------ 单条同步

    /** 按 id 回库取数再写索引：发布/下架/锁库/释放/售出/过期等任何写路径都适用 */
    public void syncById(Long itemId) {
        if (itemId == null || es.client() == null) {
            return;
        }
        try {
            Item item = itemMapper.selectById(itemId);
            if (item == null) {
                deleteById(itemId);
                return;
            }
            saveAll(List.of(item));
        } catch (Exception e) {
            es.markFailure("index-one", e);
        }
    }

    public void syncByIds(Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty() || es.client() == null) {
            return;
        }
        try {
            saveAll(itemMapper.selectBatchIds(itemIds));
        } catch (Exception e) {
            es.markFailure("index-batch", e);
        }
    }

    public void deleteById(Long itemId) {
        ElasticsearchClient client = es.client();
        if (client == null || itemId == null) {
            return;
        }
        try {
            client.delete(d -> d.index(props.getIndexName()).id(String.valueOf(itemId)));
        } catch (Exception e) {
            es.markFailure("delete", e);
        }
    }

    // -------------------------------------------------------------- 内部方法

    private int saveAll(List<Item> items) {
        ElasticsearchClient client = es.client();
        if (client == null || items == null || items.isEmpty()) {
            return 0;
        }
        Map<Long, String> categoryNames = categoryNames(items);
        List<BulkOperation> ops = new ArrayList<>(items.size());
        for (Item item : items) {
            Map<String, Object> doc = toMap(item, categoryNames.get(item.getCategoryId()));
            ops.add(BulkOperation.of(b -> b.index(IndexOperation.of(io -> io
                    .index(props.getIndexName())
                    .id(String.valueOf(item.getId()))
                    .document(doc)))));
        }
        try {
            BulkResponse response = client.bulk(b -> b.operations(ops));
            List<BulkResponseItem> failed = response.items().stream().filter(i -> i.error() != null).toList();
            if (failed.isEmpty()) {
                es.markSuccess();
            } else {
                es.markFailure("bulk", new IllegalStateException(failed.get(0).error().reason()));
            }
            return ops.size() - failed.size();
        } catch (Exception e) {
            es.markFailure("bulk", e);
            return 0;
        }
    }

    private Map<String, Object> toMap(Item item, String categoryName) {
        return json.convertValue(ItemDocument.from(item, categoryName), new TypeReference<Map<String, Object>>() {
        });
    }

    private Map<Long, String> categoryNames(List<Item> items) {
        List<Long> ids = items.stream().map(Item::getCategoryId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (ItemCategory category : categoryMapper.selectBatchIds(ids)) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }

    /** 索引状态（公开只读，方便本地排查与验收） */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", props.isEnabled());
        result.put("indexName", props.getIndexName());
        result.put("esAvailable", es.healthy());
        result.put("lastError", es.lastError());
        result.put("watermark", Instant.ofEpochMilli(watermark()).toString());
        result.put("reindexing", reindexing.get());
        ElasticsearchClient client = es.client();
        if (client != null) {
            try {
                boolean exists = indexExists(client, props.getIndexName());
                result.put("indexExists", exists);
                if (exists) {
                    result.put("docCount", client.count(c -> c.index(props.getIndexName())).count());
                    result.put("onSaleCount", client.count(c -> c.index(props.getIndexName())
                            .query(q -> q.term(t -> t.field("status").value(2)))).count());
                }
            } catch (Exception e) {
                result.put("esError", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 校验已有索引是否真的能用：直接拿索引自己的 ershou_index 分析器切一次词。
     * 索引不是本模块建的（动态 mapping）、或分词插件被换掉导致分析器不存在时都会失败，
     * 调用方据此删除重建——商品数据以 MySQL 为准，重建不丢数据。
     */
    private boolean indexUsable(String index) {
        try {
            Request request = new Request("POST", "/" + index + "/_analyze");
            request.setJsonEntity("{\"analyzer\":\"ershou_index\",\"text\":\"高等数学\"}");
            restClient.performRequest(request);
            return true;
        } catch (Exception e) {
            log.warn("[search][es] 索引 {} 不可用：{}", index, e.getMessage());
            return false;
        }
    }

    private boolean indexExists(ElasticsearchClient client, String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (Exception e) {
            throw new IllegalStateException("索引状态检查失败: " + e.getMessage(), e);
        }
    }

    private long watermark() {
        try {
            String raw = redis.opsForValue().get(RedisKeys.itemIndexWatermark());
            if (raw != null) {
                return Long.parseLong(raw);
            }
        } catch (Exception e) {
            log.warn("[search][es] 读取同步水位失败，按回看窗口处理：{}", e.getMessage());
        }
        return System.currentTimeMillis() - props.getSyncLookbackMinutes() * 60_000L;
    }

    private void setWatermark(long millis) {
        try {
            redis.opsForValue().set(RedisKeys.itemIndexWatermark(), String.valueOf(millis));
        } catch (Exception e) {
            log.warn("[search][es] 写入同步水位失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        indexExecutor.shutdownNow();
    }
}
