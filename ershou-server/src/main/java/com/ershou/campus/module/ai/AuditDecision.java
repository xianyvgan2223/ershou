package com.ershou.campus.module.ai;

public enum AuditDecision {
    /** 放行 */
    PASS,
    /** 转人工审核 */
    REVIEW,
    /** 直接拦截 */
    BLOCK
}
