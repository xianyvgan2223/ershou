package com.ershou.cloud.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 举报/仲裁工单（ershou_admin.report_ticket，结构同旧库）。
 *
 * <p>旧单体只有表、没有接口（历史遗留未实现）；T107 在 admin-service 里补管理端接口：
 * {@code GET /api/v1/admin/reports}、{@code POST /api/v1/admin/reports/{id}/handle}。
 */
@Data
@TableName("report_ticket")
public class ReportTicket {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 举报人 */
    private Long reporterId;
    /** 1=商品 2=用户 3=会话 4=面交单 */
    private Integer targetType;
    private Long targetId;
    /** 1=虚假信息 2=货不对板 3=诈骗 4=辱骂 5=违禁品 6=爽约 7=盗图 8=商家违规 9=其他 */
    private Integer reportType;
    private String description;
    /** 证据图片（JSON 数组字符串） */
    private String images;
    /** 系统聚合证据（JSON） */
    private String evidence;
    /** AI 仲裁摘要 */
    private String aiSummary;
    /** AI 责任倾向建议（仅供人工参考） */
    private String aiSuggest;
    /** 1=待受理 2=处理中 3=已判定 4=申诉中 5=已结案 */
    private Integer status;
    /** 处置结论 */
    private String decision;
    /** 处罚措施 */
    private String penalty;
    private Long handlerId;
    private LocalDateTime firstReplyAt;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
