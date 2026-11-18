package com.ershou.cloud.admin.service;

/** 管理端展示文案字典（与旧单体 ItemService.statusText / 常规状态文案逐字对齐） */
public final class AdminDicts {

    private AdminDicts() {
    }

    /** item.status：0 草稿 / 1 审核中 / 2 在售 / 3 已锁定 / 4 已售出 / 5 已下架 / 6 审核未通过 / 7 已过期 / 8 违规下架 */
    public static String itemStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "草稿";
            case 1 -> "审核中";
            case 2 -> "在售";
            case 3 -> "已锁定";
            case 4 -> "已售出";
            case 5 -> "已下架";
            case 6 -> "审核未通过";
            case 7 -> "已过期";
            case 8 -> "违规下架";
            default -> "状态" + status;
        };
    }

    /** 成色：1 全新 … 5 较重使用痕迹 */
    public static String conditionText(Integer level) {
        if (level == null) {
            return "成色未知";
        }
        return switch (level) {
            case 1 -> "全新";
            case 2 -> "几乎全新";
            case 3 -> "轻微使用痕迹";
            case 4 -> "明显使用痕迹";
            case 5 -> "较重使用痕迹";
            default -> level + " 成新";
        };
    }

    /** meetup_order.status：1 待卖家确认 / 2 待面交 / 3 已完成 / 4 已取消 / 5 已失效 / 6 爽约 / 7 纠纷中 */
    public static String orderStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "待卖家确认";
            case 2 -> "待面交";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已失效";
            case 6 -> "爽约";
            case 7 -> "纠纷中";
            default -> "状态" + status;
        };
    }

    /** sys_user.status：1 正常 / 2 限权 / 3 封禁 */
    public static String userStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "限权";
            case 3 -> "封禁";
            default -> "状态" + status;
        };
    }

    /** credit_level：1 优秀 / 2 良好 / 3 一般 / 4 风险 */
    public static String creditLevelText(Integer level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case 1 -> "优秀";
            case 2 -> "良好";
            case 3 -> "一般";
            case 4 -> "风险";
            default -> "未知";
        };
    }

    /** 举报目标类型：1=商品 2=用户 3=会话 4=面交单 */
    public static String reportTargetText(Integer targetType) {
        if (targetType == null) {
            return null;
        }
        return switch (targetType) {
            case 1 -> "商品";
            case 2 -> "用户";
            case 3 -> "会话";
            case 4 -> "面交单";
            default -> "其他";
        };
    }

    /** 举报类型：1=虚假信息 2=货不对板 3=诈骗 4=辱骂 5=违禁品 6=爽约 7=盗图 8=商家违规 9=其他 */
    public static String reportTypeText(Integer reportType) {
        if (reportType == null) {
            return null;
        }
        return switch (reportType) {
            case 1 -> "虚假信息";
            case 2 -> "货不对板";
            case 3 -> "诈骗";
            case 4 -> "辱骂";
            case 5 -> "违禁品";
            case 6 -> "爽约";
            case 7 -> "盗图";
            case 8 -> "商家违规";
            default -> "其他";
        };
    }

    /** 举报工单状态：1=待受理 2=处理中 3=已判定 4=申诉中 5=已结案 */
    public static String reportStatusText(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 1 -> "待受理";
            case 2 -> "处理中";
            case 3 -> "已判定";
            case 4 -> "申诉中";
            case 5 -> "已结案";
            default -> "状态" + status;
        };
    }
}
