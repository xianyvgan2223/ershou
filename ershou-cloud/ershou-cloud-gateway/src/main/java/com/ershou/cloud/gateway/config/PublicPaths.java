package com.ershou.cloud.gateway.config;

/**
 * 网关公开路径判定（全量微服务拆分版）。
 *
 * <p>规则与旧单体 SaTokenConfig 白名单对齐：OPTIONS 放行；登录/注册、学校、分类、商品读、搜索、
 * 地图/面交点、平台官方店公开读、用户公开主页、健康检查、WebSocket 握手放行；
 * 其余写接口、购物车/收藏/面交、聊天、管理后台、平台运营台、上传均要求登录。
 */
public final class PublicPaths {

    private PublicPaths() {
    }

    public static boolean isPublic(String method, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        // 登录/注册
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        // WebSocket 握手：鉴权由 chat-service 自己按 ?token= 处理
        if (path.equals("/ws") || path.startsWith("/ws/")) {
            return true;
        }
        // 健康检查
        if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")) {
            return true;
        }
        // 以下均为「公开读」，只放行 GET
        if (!"GET".equalsIgnoreCase(method)) {
            return false;
        }
        if (path.startsWith("/api/v1/schools")) {
            return true;
        }
        if (path.equals("/api/v1/categories") || path.startsWith("/api/v1/categories/")) {
            return true;
        }
        if (path.startsWith("/api/v1/map") || path.startsWith("/api/v1/trade-points")) {
            return true;
        }
        if (path.startsWith("/api/v1/platform/shop/")) {
            return true;
        }
        if (path.startsWith("/api/v1/search")) {
            return true;
        }
        // 用户公开主页（user-service 的 /users/{id}/profile 等只读主页）
        if (path.matches("^/api/v1/users/\\d+/(profile|public)?$")) {
            return true;
        }
        // 商品读接口：GET /items、GET /items/{id} 公开；GET /items/mine 需要登录
        if (path.equals("/api/v1/items") || path.startsWith("/api/v1/items/")) {
            return !path.startsWith("/api/v1/items/mine");
        }
        return false;
    }
}
