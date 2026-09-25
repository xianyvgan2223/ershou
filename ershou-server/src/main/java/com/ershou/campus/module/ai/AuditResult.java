package com.ershou.campus.module.ai;

import java.util.List;

public record AuditResult(AuditDecision decision, double score, List<String> labels, String reason) {

    public static AuditResult pass() {
        return new AuditResult(AuditDecision.PASS, 0.05, List.of(), null);
    }

    public static AuditResult review(String reason, List<String> labels) {
        return new AuditResult(AuditDecision.REVIEW, 0.7, labels, reason);
    }

    public static AuditResult block(String reason, List<String> labels) {
        return new AuditResult(AuditDecision.BLOCK, 0.95, labels, reason);
    }
}
