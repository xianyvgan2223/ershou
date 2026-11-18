package com.ershou.cloud.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

/** 点位审核通过（可顺带校准安全分与指引） */
@Data
public class PointApproveDTO {
    @Min(value = 0, message = "安全分不能小于 0")
    @Max(value = 100, message = "安全分不能大于 100")
    private Integer safetyScore;
    private String guideText;
    private List<String> safeTags;
}
