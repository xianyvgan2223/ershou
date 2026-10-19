package com.ershou.cloud.gateway.config;

/**
 * 网关公开路径判定（第 5 轮设计 4.1 / Lead 派工：GET 公开白名单）。
 *
 * <p>规则与旧单体 SaTokenConfig 的白名单对齐，另外补充：
 * <ul>
 *   <li>OPTIONS 预检一律放行（CORS 浏览器行为，不可能带 token）；</li>
 *   <li>{@code GET /api/v1/items/**} 公开，但 {@code GET /api/v1/items/mine}（我的发布，写路径仍在单体）需要登录；</li>
 *   <li>未迁移的公开只读接口（map/trade-points/platform/shop/search status/用户主页）继续公开，
 *       避免经网关反代后反而要登录、功能中断。</li>
 * </ul>
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
        // 登录/注册（旧单体与 user-service 都放在 /auth 下）
        if (path.startsWith("/api/v1/auth/")) {
            return true;
        }
        // 下面均为「公开读」：统一只放行 GET
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
        if (path.startsWith("/api/v1/search/status")) {
            return true;
        }
        // 用户公开主页（单体 UserService.publicProfile）
        if (path.matches("^/api/v1/users/\\d+/profile$")) {
            return true;
        }
        // 健康检查（容器/QA 探活）
        if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")) {
            return true;
        }
        // WebSocket 握手暂留单体，其鉴权由聊天模块自己按 ?token= 处理
        if (path.equals("/ws") || path.startsWith("/ws/")) {
            return true;
        }
        // 商品读接口：GET /items、GET /items/{id} 公开；GET /items/mine 是我的发布，需登录
        if (path.equals("/api/v1/items") || path.startsWith("/api/v1/items/")) {
            return !path.startsWith("/api/v1/items/mine");
        }
        return false;
    }
}
