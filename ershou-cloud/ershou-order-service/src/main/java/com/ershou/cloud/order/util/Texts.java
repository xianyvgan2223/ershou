package com.ershou.cloud.order.util;

/** 中文状态文案（与旧单体 ItemService/OrderService 的 statusText 完全一致，前端直接展示）。 */
public final class Texts {

    private Texts() {
    }

    /** 商品状态：0 草稿 / 1 审核中 / 2 在售 / 3 已锁定 / 4 已售出 / 5 已下架 / 6 审核未通过 / 7 已过期 / 8 违规下架 */
    public static String itemStatus(Integer status) {
        if (status == null) {
            return "";
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
            default -> "未知";
        };
    }

    /** 面交单状态：1 待卖家确认 / 2 待面交 / 3 已完成 / 4 已取消 / 5 已失效 / 6 爽约 / 7 纠纷中 */
    public static String orderStatus(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "待卖家确认";
            case 2 -> "待面交";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已失效";
            case 6 -> "爽约";
            case 7 -> "纠纷中";
            default -> "未知";
        };
    }

    /** 成色：1 全新 / 2 几乎全新 / 3 轻微使用 / 4 明显使用 / 5 有瑕疵 */
    public static String condition(Integer level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case 1 -> "全新";
            case 2 -> "几乎全新";
            case 3 -> "轻微使用";
            case 4 -> "明显使用";
            case 5 -> "有瑕疵";
            default -> "";
        };
    }
}
