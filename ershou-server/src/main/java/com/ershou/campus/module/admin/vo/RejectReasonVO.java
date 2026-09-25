package com.ershou.campus.module.admin.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 驳回原因模板项 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectReasonVO {
    private String code;
    private String label;
    private String scope;
}
