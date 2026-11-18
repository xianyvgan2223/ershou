# 全量微服务拆分交付说明（2026-11-13）

## 结果

旧单体 `ershou-server` 的剩余模块已全部拆成微服务，网关不再反代旧单体 8080。
父工程 `ershou-cloud` 已聚合 **11 个模块（8 服务 + common + gateway + 父工程）**，全量构建通过，网关全链路 8/8 PASS。

| 服务 | 端口 | 数据库 | 状态 |
| --- | --- | --- | --- |
| gateway | 8088 | — | 运行中 |
| user-service | 8081 | ershou_user | 运行中 |
| item-service | 8082 | ershou_item | 运行中（ES 索引 19/17） |
| order-service | 8083 | ershou_order | 运行中（Seata/RabbitMQ 正常） |
| chat-service | 8084 | ershou_chat | 运行中（REST + WS） |
| map-service | 8085 | ershou_map | 运行中（Redis GEO） |
| platform-service | 8086 | ershou_platform | 运行中（Lua 库存） |
| admin-service | 8087 | ershou_admin | 运行中（BFF + WS） |
| file-service | 8089 | —（MinIO） | 运行中 |

## 网关路由（旧 8080 已无路由）

| 路径 | 目标 |
| --- | --- |
| `/api/v1/auth/**`、`/api/v1/users/**`、`/api/v1/schools/**` | user-service |
| `/api/v1/items/**`、`/api/v1/categories/**`、`/api/v1/search/**` | item-service |
| `/api/v1/cart/**`、`/api/v1/favorites/**`、`/api/v1/meetup-orders/**` | order-service |
| `/api/v1/chat/**`、`/ws/**` | chat-service |
| `/ws/admin/**` | admin-service |
| `/api/v1/map/**`、`/api/v1/trade-points/**` | map-service |
| `/api/v1/platform/**` | platform-service |
| `/api/v1/admin/**` | admin-service |
| `/api/v1/files/**` | file-service |

## 数据库拆分

| 库 | 归属表 |
| --- | --- |
| ershou_user | sys_user、school、campus、user_block |
| ershou_item | item、item_category、item_review、undo_log |
| ershou_order | cart_item、favorite、favorite_folder、meetup_order、credit_log、notification、review、undo_log |
| ershou_chat | chat_session、chat_message |
| ershou_map | trade_point |
| ershou_platform | platform_consign_point、platform_coupon、platform_coupon_user、platform_flash_sale、platform_flash_sale_item、platform_seckill_record、consign_order |
| ershou_admin | sys_audit_log、report_ticket、ai_audit_record |

迁移 SQL：`ershou-cloud/sql/split-*.sql`（幂等，可重复执行；数据从旧库 `ershou` 复制）。

## 构建与验收证据

- 父工程全量构建：`mvn -f ershou-cloud/pom.xml -DskipTests package` → **BUILD SUCCESS（11/11 模块）**
- 全链路验收：`scripts\verify-full-split.ps1 -Gateway http://127.0.0.1:8088 -UserPhone 19900000102` → **PASS 8 / FAIL 0 / SKIP 0**（user/item/order/chat/map/platform/admin/file）
- 管理端（经网关、管理员 token）：dashboard/users/items/review/feed/audit-logs/audit/points 全部 `code=0`
- 用户端（经网关、Bearer）：users/me、items/mine、cart、meetup-orders、chat/sessions、platform/shop/overview 全部 `code=0`
- Nacos：9 个服务全部注册 `127.0.0.1` healthy；9 个 dataId 配置已发布
- 文件上传：经网关 multipart → MinIO URL 公开访问 HTTP 200

## 启动 / 停止

```powershell
# 启动全部（含 Nacos 配置发布；Docker 中间件需先自行启动）
powershell -ExecutionPolicy Bypass -File scripts\start-full-cloud.ps1

# 只看状态
powershell -ExecutionPolicy Bypass -File scripts\start-full-cloud.ps1 -Status

# 全链路验收
powershell -ExecutionPolicy Bypass -File scripts\verify-full-split.ps1 -Gateway http://127.0.0.1:8088 -UserPhone 19900000102
```

## 分布式锁：已从 SETNX 切换为 Redisson

三个服务引入 `org.redisson:redisson:3.35.0` + `RedissonConfig`（复用各自 `spring.data.redis` 的 host/port/password/database）：

| 服务 | 锁场景 | 实现 |
| --- | --- | --- |
| item-service | 商品缓存重建锁（防击穿） | `RLock.tryLock(0, TimeUnit.SECONDS)`，watchdog 自动续期，`isHeldByCurrentThread()` 校验后释放 |
| map-service | GEO 索引重建锁 | `RLock.tryLock(0, geoRebuildLockSeconds, SECONDS)`，finally 释放 |
| platform-service | 缓存预热锁 | `RLock.tryLock(0, 30, SECONDS)`，替代原 token + SETNX + 比较删除 |

保留不变（它们不是锁）：
- MQ 消费幂等键：`SETNX order:{orderId} EX 24h`（去重标记，必须保留 24h）
- 券/秒杀库存：Redis Lua `DefaultRedisScript` 原子扣减
- 订单状态/超卖：DB 条件 UPDATE + Seata AT

验证：三个服务日志均出现 `Redisson 3.35.0` + `Connections initialized 127.0.0.1:6380`；全链路验收 8/8 PASS。

## 已知遗留（非阻塞）

1. 收藏态 `favorited` 暂恒 false：favorite 表在 order-service，item-service 未接 `/inner/favorites`。
2. admin 审核实时广播未迁移：首版 `/ws/admin/review` 仅握手/心跳，前端靠轮询。
3. platform 运营台 `gmvToday/gmvTotal/orderToday` 恒 0：order-service 平台订单 summary 未提供 GMV/今日口径。
4. 部分 admin/map/order inner 字段缺口（如 item 管理列表 condition/images/sellerPhone、map 审核 reason、order 确认时间），admin 侧已做部分补齐/降级。
5. order → chat 私聊提醒未接（契约无内部发送接口）。
6. 平台单完成收口缺 `POST /inner/items/{id}/platform-settle`，当前降级仅告警。
7. 测试数据痕迹：meetup_order 63/64/65/66/67、秒杀记录、item 893/895 下架、888/889 库存变化；演示前可重跑 `split-*.sql` 回到基线。
8. 旧单体 `ershou-server` 保留在仓库作参考，但网关不再路由；生产化需移除。

## 关键文件

- 拆分契约：`ershou-cloud/docs/full-split-contract.md`
- 本说明：`ershou-cloud/docs/full-split-summary.md`
- 网关路由：`ershou-cloud/ershou-cloud-gateway/src/main/java/com/ershou/cloud/gateway/config/GatewayRoutesConfig.java`
- 验收脚本：`scripts/verify-full-split.ps1`
- 启动脚本：`scripts/start-full-cloud.ps1`
