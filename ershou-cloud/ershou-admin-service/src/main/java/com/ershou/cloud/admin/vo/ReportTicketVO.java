package com.ershou.cloud.admin.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 举报工单视图（ershou_admin.report_ticket，T107 新增管理接口） */
@Data
public class ReportTicketVO {
    private Long id;
    private Long reporterId;
    private String reporterNickname;
    /** 1=商品 2=用户 3=会话 4=面交单 */
    private Integer targetType;
    private String targetTypeText;
    private Long targetId;
    /** 1=虚假信息 2=货不对板 3=诈骗 4=辱骂 5=违禁品 6=爽约 7=盗图 8=商家违规 9=其他 */
    private Integer reportType;
    private String reportTypeText;
    private String description;
    private String images;
    private String evidence;
    private String aiSummary;
    private String aiSuggest;
    private Integer status;
    private String statusText;
    private String decision;
    private String penalty;
    private Long handlerId;
    private String handlerNickname;
    private LocalDateTime firstReplyAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
