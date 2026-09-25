package com.ershou.campus.module.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ershou.campus.module.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * ES 客户端门面：统一做可用性判断与故障熔断。
 * 目的：ES 挂掉时不要在每次搜索上都耗一次连接超时，直接走降级路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsGateway {

    private final ObjectProvider<ElasticsearchClient> clientProvider;
    private final SearchProperties props;

    private volatile long downUntilMs = 0L;
    private volatile String lastError;

    /** 开关打开、不在熔断窗口内、且客户端存在 */
    public boolean healthy() {
        return props.isEnabled()
                && System.currentTimeMillis() >= downUntilMs
                && clientProvider.getIfAvailable() != null;
    }

    /** 需要真正访问 ES 时调用；返 null 表示当前不可用 */
    public ElasticsearchClient client() {
        return healthy() ? clientProvider.getIfAvailable() : null;
    }

    public void markFailure(String op, Exception e) {
        lastError = op + ": " + rootMessage(e);
        downUntilMs = System.currentTimeMillis() + props.getFailureBackoffMs();
        log.warn("[search][es] {} 失败，{}ms 内降级 MySQL LIKE：{}", op, props.getFailureBackoffMs(), rootMessage(e));
    }

    /** 调用成功后复位熔断与上次错误，让 /search/status 反映的是"当前"状态而不是历史报错 */
    public void markSuccess() {
        lastError = null;
        downUntilMs = 0L;
    }

    public String lastError() {
        return lastError;
    }

    public long downUntilMs() {
        return downUntilMs;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
