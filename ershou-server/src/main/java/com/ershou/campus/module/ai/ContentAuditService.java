package com.ershou.campus.module.ai;

/**
 * 内容审核能力抽象：MVP 用本地关键词规则实现（零成本、可离线）；
 * 后续可替换为 Ollama 本地模型或云内容安全 API，业务层不用改。
 */
public interface ContentAuditService {

    /**
     * @param text  待审文本（标题 + 描述）
     * @param scene 场景：item / chat / report
     */
    AuditResult auditText(String text, String scene);
}
