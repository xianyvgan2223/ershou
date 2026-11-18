# ershou-admin-service（运营后台微服务，:8087）

全量拆分 **T107** 产物：把旧单体 `ershou-server` 的 `module/admin`（25 个 `/api/v1/admin/**` 接口）
迁移成独立微服务；路由前缀 `/api/v1/admin/**`（网关侧由 Lead/platform-dev 配置转发到本服务）。

架构定位是 **BFF + 自己的审计库**：

- 自己的库 `ershou_admin`：`sys_audit_log`（操作审计）、`report_ticket`（举报工单）、`ai_audit_record`（AI 审核记录）；
- 用户/商品/面交单/点位数据**不跨库**，通过 user/item/order/map 服务的 `/inner/**` OpenFeign 获取与处置；
- 管理员判定：网关注入 `X-User-Id` → 调 user-service `GET /inner/users/{id}` 判 `isAdmin/roleFlags`；
- 路径与响应 JSON 与单体保持一致，Web 端零改动。

## 一、端点清单

### 1.1 迁移自单体（25 个，路径/JSON 不变）

| 模块 | 方法与路径 | 说明 | 依赖 |
| --- | --- | --- | --- |
| 概览 | `GET /api/v1/admin/dashboard` | 14 项运营概览（键名同单体） | item/user/map/order 统计 + 本地审计 |
| 审核工作台 | `GET /api/v1/admin/audit/items` | 商品审核队列（默认 status=1） | item `/inner/items` 列表 |
|  | `POST /api/v1/admin/audit/items/{id}/approve` | 审核通过（1→2） | item `/inner/items/{id}/status` |
|  | `POST /api/v1/admin/audit/items/{id}/reject` | 审核驳回（1→6，原因必填） | 同上 + reason/rejectCode |
|  | `POST /api/v1/admin/audit/items/batch` | 批量通过/驳回（逐条结果） | 同上 |
|  | `GET /api/v1/admin/audit/reject-reasons` | 驳回原因模板（item/point） | 本地字典 |
|  | `GET /api/v1/admin/audit/points` | 点位审核列表（默认待审） | map `/inner/points`（本地筛选分页） |
|  | `POST /api/v1/admin/audit/points/{id}/approve` | 点位审核通过 | map `/inner/points/{id}/audit?action=approve` |
|  | `POST /api/v1/admin/audit/points/{id}/reject` | 点位审核驳回（原因必填） | map audit action=reject |
|  | `POST /api/v1/admin/points/{id}/status` | 启用/停用点位（停用须原因） | map `/inner/points/{id}/status` |
| 管理 | `POST /api/v1/admin/trade-points/cleanup-empty` | 清理无商品空点位 | map `/inner/points/cleanup-empty` |
|  | `GET /api/v1/admin/items` | 商品管理列表（全状态/关键词/卖家） | item `/inner/items` 列表 |
|  | `POST /api/v1/admin/items/{id}/offline` | 违规强制下架（→8，原因必填） | item status |
|  | `GET /api/v1/admin/orders` | 面交单查询（订单号/标题） | order `/inner/orders` |
|  | `GET /api/v1/admin/users` | 用户列表（昵称/店铺/手机号） | user `/inner/users` |
|  | `POST /api/v1/admin/users/{id}/status` | 封禁/解封/限权 | user `/inner/users/{id}/status` |
|  | `POST /api/v1/admin/users/{id}/credit` | 信用分调整（±100） | user `/inner/users/{id}/credit` |
|  | `GET /api/v1/admin/audit-logs` | 操作审计日志（本服务库） | 本地 `sys_audit_log` |
| 上架审核 | `GET /api/v1/admin/review/feed` | 上架审核队列（scope=pending/done/all） | item `/inner/item-reviews` |
|  | `GET /api/v1/admin/review/stats` | 审核统计（待审/今日已审/在线审核员） | item `/inner/item-reviews/stats` |
|  | `GET /api/v1/admin/review/items/{itemId}` | 单条审核详情 | item `/inner/item-reviews/{itemId}` |
|  | `POST /api/v1/admin/review/items/{itemId}/reviewed` | 已审核（确认无问题） | item `/inner/item-reviews/{itemId}/reviewed` |
|  | `POST /api/v1/admin/review/items/{itemId}/offline` | 审核下架 | item `/inner/item-reviews/{itemId}/offline` |
|  | `POST /api/v1/admin/review/batch/{action}` | 批量 review/offline（≤50） | item `/inner/item-reviews/batch` |
|  | `POST /api/v1/admin/review/backfill` | 补登记审核队列 | item `/inner/item-reviews/backfill` |
| 实时 | `WS /ws/admin/review?token=` | 审核实时推送（握手鉴权 + ready/ping-pong；广播见遗留） | Sa-Token 共享会话 + user inner 判权 |

### 1.2 T107 新增（Lead 已批准，仅本地域表）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/v1/admin/ai-audit-records` | AI 审核记录只读列表（bizType/bizId/decision 筛选） |
| `GET /api/v1/admin/reports` | 举报工单列表（status/reportType/reporterId 筛选） |
| `POST /api/v1/admin/reports/{id}/handle` | 举报工单处置（写处置结论/处罚/状态，落审计日志） |

所有端点先 `requireAdminId()`：未登录 10002 / 非管理员 10003；下游不可用统一 80001（Feign fallback）。

## 二、数据表（ershou_admin）

建库/建表/迁移脚本：`../sql/split-admin.sql`（可重复执行；数据 `INSERT IGNORE ... SELECT` 自旧库 `ershou` 复制）。

| 表 | 来源 | 说明 |
| --- | --- | --- |
| `sys_audit_log` | 复制自 `ershou.sys_audit_log`（893 行） | 运营操作审计，本服务新写入口 |
| `report_ticket` | 复制自 `ershou.report_ticket`（0 行） | 举报/仲裁工单（新增管理接口） |
| `ai_audit_record` | 复制自 `ershou.ai_audit_record`（0 行） | AI 审核记录（契约第六节：归 admin 库，item-service 不建/不写） |

## 三、Feign 内部依赖（跨服务只读/写，禁止跨库 SQL）

| 服务 | 调用的 /inner 接口 | 用途 | 现状（2026-11-13） |
| --- | --- | --- | --- |
| user-service | `GET /inner/users/{id}`、`/batch`、`/users`、`/users/stats`、`POST /users/{id}/status`、`POST /users/{id}/credit`、`GET /inner/schools`、`/inner/campuses` | 管理员判定、用户管理、校区名补充 | ✅ 已实现可用 |
| item-service | `GET /inner/items`（管理列表）、`/items/{id}`、`/items/batch`、`POST /items/{id}/status`、`/items/stats`、`GET /inner/item-reviews/**`（7 个） | 商品管理/审核工作台 | ✅ 已实现可用（T104 补充 `/inner/items` 列表后转绿；字段缺口见遗留） |
| order-service | `GET /inner/orders`（userId/status/keyword 分页）、`/orders/stats`、`/orders/{id}` | 面交单查询 | ✅ 已实现可用 |
| map-service | `GET /inner/points`（全量）、`/points/stats`、`POST /points/{id}/audit`、`POST /points/{id}/status`、`POST /points/cleanup-empty` | 点位审核/启停/清理 | ✅ 已实现可用（带缺口，见遗留） |
| chat-service | — | 本服务当前无依赖 | — |
| platform-service | — | 平台运营台 `/api/v1/platform/admin/**` 由 platform-service 自己承载 | — |

## 四、配置

- 本地 `src/main/resources/application.yml`：仅端口 8087 / 服务名 / Nacos 地址 / `spring.config.import`；
- Nacos dataId `ershou-admin-service.yaml`（`../nacos-configs/ershou-admin-service.yaml`）：MySQL `ershou_admin`、Redis **db0**（仅 Sa-Token 共享会话）、MyBatis-Plus、Feign(+Sentinel 降级)、`ershou.admin.*`、`ershou.review.*`；
- 兜底管理员：`ershou.admin.bootstrap-user-ids`（默认空=关闭，仅 user-service 不可用且演示/应急时使用，生产不要配置）。

## 五、构建与启动

```powershell
# 构建（parent/common 已在本地仓库）
mvn -f ershou-cloud/ershou-admin-service/pom.xml -DskipTests package

# 启动（先确保 Nacos 8848 / MySQL / Redis 6380 可用）
java -jar ershou-cloud/ershou-admin-service/target/ershou-admin-service-1.0.0.jar
```

启动后：Tomcat 8087，Nacos 注册 `ershou-admin-service`（127.0.0.1:8087），`/actuator/health` → UP。

## 六、冒烟结果（2026-11-13，管理员 X-User-Id=3；直连 8087 + 经网关 8088）

| 用例 | 结果 |
| --- | --- |
| `GET /admin/audit-logs?pageSize=2` | code=0，total=895（分页正确；含本服务新写审计） |
| 真实管理员 id=3 / id=1002 | code=0（user-service `/inner/users/{id}` 判权通过） |
| 普通用户 id=1 调管理接口 | 10003 |
| 无身份头 | 10002 |
| `GET /admin/dashboard` | code=0；pendingItems=29 / onSaleItems=17 / totalItems=19 / totalUsers=192 / totalOrders=6 / audit 打通 |
| `GET /admin/items`、`/admin/audit/items` | code=0（total=19；item-service 补列表接口后转绿） |
| `GET /admin/review/feed|stats|items/{id}` | code=0（pending=29、onSaleTotal=17、详情 actionable=true） |
| `GET /admin/users` | code=0，total=192，手机号脱敏、statusText/creditLevelText 正确 |
| `GET /admin/orders` | code=0，total=6，buyer/seller 昵称正确 |
| `GET /admin/audit/points?auditStatus=-1` | code=0，total=21，campusName 由 user inner 补齐 |
| `GET /admin/audit/reject-reasons` | code=0，item 8 条 / point 6 条 |
| `GET /admin/ai-audit-records`、`/admin/reports` | code=0（表空，符合旧数据现状） |
| 举报工单：插入→处置→审计→清理 | 处置成功（status=5、handler=3、closedAt 落库）+ 审计 `report/report-handle` |
| 用户管理写路径（幂等 status=1） | code=0；审计表新增 `module=user/update-status` 记录 |
| 写接口普通用户负向（approve/users-status/cleanup/report-handle） | 全部 10003（调下游前拦截，无副作用） |
| WS `/ws/admin/review` 无 token/伪造 token | 401（直连 8087；握手鉴权生效） |
| 经网关 8088 + 管理员 token | `/admin/audit-logs`、`/dashboard`、`/users`、`/review/feed`、`/review/stats`、`/audit/reject-reasons`、`/audit/points` 均 code=0；普通用户 10003；无 token 401 |
| 构建 | `mvn -f ershou-cloud/ershou-admin-service/pom.xml -DskipTests package` **BUILD SUCCESS**（jar 67.9 MB）；8087 启动 12.5s、Nacos healthy、`/actuator/health` UP |

## 七、遗留 / 需协调

1. **item-service inner 字段缺口**（接口已可用，字段不全）：`GET /inner/items` 管理列表未返回 `conditionLevel/images/preferPointName/auditReason/rejectCode/viewCount/favCount/expireAt/sellerNickname/sellerPhone/sellerCreditScore` → admin 侧已用 user-service `/inner/users/batch` 补 `sellerNickname/sellerCreditScore`，其余字段为空/0；`POST /inner/items/{id}/status` 只收 `status+reason`，**不收 `rejectCode`**，驳回原因模板 code 无法落 `item.reject_code`（建议 item 侧加可选参数）。
2. **user-service inner 缺口**：`InnerUserVO`（`/inner/users/{id}`、`/batch`）无手机号/风控字段 → 商品/审核列表 `sellerPhone`、`POST /admin/users/{id}/status|credit` 响应里的 `phone` 为空（列表接口有手机号，前端操作后会刷新列表，影响可控）。建议给内部接口加 `withPhone` 或 admin 专用单查。
3. **map-service inner 缺口**：audit 只收 action（不收 safetyScore/guideText/safeTags/驳回原因）；status 不收 reason；列表无分页/auditStatus/keyword；`InnerPointVO` 无 campusName/creator → admin 侧已本地补 campusName 并在审计日志保留原因，其余降级。
4. **order-service inner 缺口**：`/inner/orders` 仅支持单一 `userId`（买家或卖家参与），无法同时按 buyerId+sellerId 过滤（admin 两个都传时按 buyerId 过滤）；`OrderVO` 只有 `buyerConfirmed/sellerConfirmed` 布尔、无确认时间 → `AdminOrderVO.buyerConfirmAt/sellerConfirmAt` 留空。
5. **审核实时广播未迁移**：旧单体由 `ItemReviewService` 直接 broadcast；拆分后事件源在 item-service，需要 MQ/Redis Pub/Sub 配合。首版 WS 已实现握手鉴权/ready/ping-pong/在线审核员计数（`/inner/item-reviews/stats.onlineReviewers`），前端不报错但不会实时刷新。
6. **网关 WebSocket 路由缺口**：gateway 当前 `/ws/**` 整体转发 chat-service，`/ws/admin/review` 被错误路由（实测直连 8087 返回 401，经网关返回 101）→ 需在 chat 的 `/ws/**` 路由**之前**加 `/ws/admin/**` → `lb://ershou-admin-service`（gateway 属 Lead/platform-dev scope，本服务无法修改）。
7. **`ershou-cloud/pom.xml` 未修改**（契约禁止）：本模块未加入 reactor，按 `-f` 单独构建；部署脚本/父 pom 聚合由 Lead 统一收口。
8. 按 Lead 收尾指令，8087 已在冒烟后停止、不再重启；最终 jar 已构建（`target/ershou-admin-service-1.0.0.jar`），等统一重启验收。
