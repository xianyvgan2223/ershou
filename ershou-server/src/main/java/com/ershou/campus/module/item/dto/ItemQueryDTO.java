package com.ershou.campus.module.item.dto;

import lombok.Data;

@Data
public class ItemQueryDTO {
    private long pageNum = 1;
    private long pageSize = 20;
    private String keyword;
    private Long categoryId;
    private Long campusId;
    private Long sellerId;
    /** latest | price_asc | price_desc */
    private String sort = "latest";
}
