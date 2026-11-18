package com.ershou.cloud.platform.feign.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 校区内部视图（/inner/campuses 契约；代卖点列表要显示「XX 校区」）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InnerCampusVO {

    private Long id;
    private String name;
    private Long schoolId;
}
