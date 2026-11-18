package com.ershou.cloud.chat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 批量删除结果：成功数 + 逐条失败原因（结构与旧单体 BatchAuditResultVO 一致） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchAuditResultVO {
    private int success;
    private int failed;
    private List<Failure> failures;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Failure {
        private Long id;
        private String reason;
    }
}
