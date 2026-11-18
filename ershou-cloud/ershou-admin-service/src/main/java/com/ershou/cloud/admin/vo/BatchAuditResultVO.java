package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.util.List;

/** 批量处置结果（字段与旧单体 BatchAuditResultVO 完全一致） */
@Data
public class BatchAuditResultVO {
    private int success;
    private int failed;
    private List<Failure> failures;

    @Data
    public static class Failure {
        private Long id;
        private String reason;

        public Failure() {
        }

        public Failure(Long id, String reason) {
            this.id = id;
            this.reason = reason;
        }
    }
}
