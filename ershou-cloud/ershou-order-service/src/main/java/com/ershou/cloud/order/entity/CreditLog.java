package com.ershou.cloud.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信用分流水（第 103 轮从单体迁入 order-service，交易域自有 {@code ershou_order} 库）。
 *
 * <p>写入口：
 * <ul>
 *   <li>面交完成自动加/减分（{@code CreditService#addCredit}，bizType=order）；</li>
 *   <li>admin-service 人工调整：调本服务 {@code POST /inner/credit-logs} 留痕（bizType=admin）。</li>
 * </ul>
 * 说明：{@code sys_user.credit_score} 在 user-service 库，真正改分走 Feign
 * {@code POST /inner/users/{id}/credit?delta=}；本表只做流水/对账。
 */
@Data
@TableName("credit_log")
public class CreditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 分数变化（正负），如 +2 / -2 */
    private Integer changeValue;

    private Integer beforeScore;

    private Integer afterScore;

    private String reason;

    /** 业务类型：order=面交完成自动加/扣分；admin=运营人工调整 */
    private String bizType;

    /** 关联业务 id：bizType=order 时为面交单 id */
    private Long bizId;

    /** 运营操作人（人工调整时有值） */
    private Long operatorId;

    private LocalDateTime createdAt;
}
