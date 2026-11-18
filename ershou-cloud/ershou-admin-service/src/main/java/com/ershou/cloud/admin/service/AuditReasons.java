package com.ershou.cloud.admin.service;

import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.ResultCode;

import java.util.List;

/**
 * 模板化驳回原因（与旧单体 AuditReasons 完全一致；FR-ADM-01）。
 *
 * <p>纯静态字典，留在 admin-service 本地（不下发 DB，避免为几个文案拆表）。
 */
public final class AuditReasons {

    public static final String SCOPE_ITEM = "item";
    public static final String SCOPE_POINT = "point";

    /** code 落库到 item.reject_code / 审计日志，label 用于拼装可读理由 */
    public record Reason(String code, String label, String scope) {
    }

    private static final List<Reason> ITEM_REASONS = List.of(
            new Reason("PROHIBITED", "违禁或限售商品", SCOPE_ITEM),
            new Reason("FALSE_DESC", "虚假或夸大描述", SCOPE_ITEM),
            new Reason("CONTACT", "含站外联系方式或广告刷屏", SCOPE_ITEM),
            new Reason("ILLEGAL_IMAGE", "图片违规或与描述不符", SCOPE_ITEM),
            new Reason("INCOMPLETE", "信息不完整或分类错误", SCOPE_ITEM),
            new Reason("ABNORMAL_PRICE", "异常价格引流", SCOPE_ITEM),
            new Reason("DUPLICATE", "重复发布", SCOPE_ITEM),
            new Reason("OTHER", "其他（请补充说明）", SCOPE_ITEM)
    );

    private static final List<Reason> POINT_REASONS = List.of(
            new Reason("DUPLICATE", "与已有面交点重复", SCOPE_POINT),
            new Reason("INCOMPLETE", "名称或图文指引不完整", SCOPE_POINT),
            new Reason("UNSAFE", "位置存在安全隐患", SCOPE_POINT),
            new Reason("MISLOCATED", "坐标明显偏移或无法定位", SCOPE_POINT),
            new Reason("INVALID", "不是校内有效面交点", SCOPE_POINT),
            new Reason("OTHER", "其他（请补充说明）", SCOPE_POINT)
    );

    private AuditReasons() {
    }

    public static List<Reason> of(String scope) {
        return SCOPE_POINT.equalsIgnoreCase(scope) ? POINT_REASONS : ITEM_REASONS;
    }

    public static String labelOf(String scope, String code) {
        if (code == null) {
            return null;
        }
        return of(scope).stream()
                .filter(r -> r.code().equals(code))
                .map(Reason::label)
                .findFirst()
                .orElse(null);
    }

    /** 把模板 code 与补充说明拼成可读理由；两者都为空则拒绝 */
    public static String compose(String scope, String code, String detail) {
        String label = labelOf(scope, code);
        String extra = detail == null ? "" : detail.trim();
        if (label == null && extra.isEmpty()) {
            throw new BizException(ResultCode.ADMIN_REASON_REQUIRED);
        }
        if (label == null) {
            return extra;
        }
        if (extra.isEmpty() || extra.equals(label)) {
            return label;
        }
        return label + "：" + extra;
    }
}
