package com.ershou.campus.module.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class KeywordContentAuditService implements ContentAuditService {

    private final List<String> blockKeywords;
    private final List<String> reviewKeywords;

    public KeywordContentAuditService(@Value("${ershou.ai.block-keywords:}") String block,
                                      @Value("${ershou.ai.review-keywords:}") String review) {
        this.blockKeywords = split(block);
        this.reviewKeywords = split(review);
        log.info("内容审核已加载：禁售词 {} 个，可疑词 {} 个", blockKeywords.size(), reviewKeywords.size());
    }

    @Override
    public AuditResult auditText(String text, String scene) {
        if (text == null || text.isBlank()) {
            return AuditResult.pass();
        }
        String lower = text.toLowerCase();

        List<String> hitBlock = new ArrayList<>();
        for (String kw : blockKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                hitBlock.add(kw);
            }
        }
        if (!hitBlock.isEmpty()) {
            String reason = "内容涉嫌违规：" + String.join("、", hitBlock) + "（禁售品类）";
            log.warn("[ai][audit][{}][BLOCK] hit={}", scene, hitBlock);
            return AuditResult.block(reason, hitBlock);
        }

        List<String> hitReview = new ArrayList<>();
        for (String kw : reviewKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                hitReview.add(kw);
            }
        }
        if (!hitReview.isEmpty()) {
            log.info("[ai][audit][{}][REVIEW] hit={}", scene, hitReview);
            return AuditResult.review("包含可疑信息：" + String.join("、", hitReview) + "，已转人工复核", hitReview);
        }
        return AuditResult.pass();
    }

    private List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
