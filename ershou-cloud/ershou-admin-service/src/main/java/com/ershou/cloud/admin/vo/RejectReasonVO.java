package com.ershou.cloud.admin.vo;

import lombok.Data;

/** 驳回原因模板（字段与旧单体 RejectReasonVO 一致） */
@Data
public class RejectReasonVO {
    private String code;
    private String label;
    private String scope;

    public RejectReasonVO() {
    }

    public RejectReasonVO(String code, String label, String scope) {
        this.code = code;
        this.label = label;
        this.scope = scope;
    }
}
