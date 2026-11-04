# T36 安全审计：管理后台 + 平台运营台（页面与接口）

| 项 | 值 |
| --- | --- |
| 审计人 | `admin-member`（T36） |
| 审计时间 | 2026-10-21 11:xx（Asia/Shanghai） |
| 审计对象 | Web 运营后台 `views/admin/**`（8 个）、平台运营台 `views/console/**`（8 个）、后端 `module/admin/**`、`module/platform/controller/{PlatformConsoleController,PlatformAdminController,PlatformShopController,PlatformMeController}`、`AdminAuthService` |
| 审计方法 | **静态代码审计为主**（逐接口/逐页面读代码 + grep 交叉验证）；运行时只读验证**未执行**：审计时 8080/8088/8081/8082/8083 全部未监听（`Get-NetTCPConnection` 实测），且纪律禁止启动服务与任何写操作 |
| 纪律 | 未修改任何源码/配置/DB；未调用任何管理写接口；未启停服务 |
| 结论 | **P0=0，P1=3，P2=4，P3=11（共 18 条）**；管理接口鉴权覆盖 49/49（未发现"漏鉴权"接口）；最大风险来自**认证环节（固定验证码）**与**运营台写操作零审计** |

---

## 一、页面清单表（页面 → 路由 → 主要接口 → 鉴权）

### 1.1 管理后台（`ershou-web/src/views/admin/**`，挂在 `/admin` 下）

路由定义：`ershou-web/src/router/index.ts:110-166`（父 `AdminShell` + 7 个子路由 + 旧路径 redirect）；前端守卫 `ershou-web/src/router/index.ts:231-251`。

| 页面 | 路由 | 主要接口（后端） | 后端鉴权 |
| --- | --- | --- | --- |
| `AdminShell.vue`（后台外壳/导航/角标） | `/admin` | `GET /admin/review/stats`；`GET /admin/dashboard`（store） | 均在方法内 `requireAdminId()`：`AdminReviewController.java:71`、`AdminAuditController.java:46` |
| `AdminDashboardView.vue`（运营概览） | `/admin`（`admin-dashboard`） | `GET /admin/dashboard`、`GET /admin/audit-logs`（最近操作） | `AdminAuditController.java:46`、`AdminManageController.java:108` |
| `AdminAuditView.vue`（审核工作台：商品/点位） | `/admin/audit` | `GET /admin/audit/items`、`POST /admin/audit/items/{id}/approve\|reject`、`POST /admin/audit/items/batch`、`GET /admin/audit/reject-reasons`、`GET /admin/audit/points`、`POST /admin/audit/points/{id}/approve\|reject`、`POST /admin/points/{id}/status` | `AdminAuditController.java:56,63,70,77,83,97,105,112,119` |
| `AdminReviewView.vue`（上架审核，含 WS 实时） | `/admin/review` | `GET /admin/review/feed\|stats\|items/{itemId}`、`POST /admin/review/items/{itemId}/reviewed\|offline`、`POST /admin/review/batch/{action}`、`POST /admin/review/backfill`；`ws://.../ws/admin/review?token=` | `AdminReviewController.java:44,71,78,86,94,102,109`；WS 握手独立校验 admin：`AdminReviewHandshakeInterceptor.java:44-61` |
| `AdminItemsView.vue`（商品管理/强制下架/行内审核） | `/admin/items` | `GET /admin/items`、`POST /admin/items/{id}/offline`、`GET /admin/audit/reject-reasons`、`POST /admin/audit/items/{id}/approve\|reject`、`POST /admin/audit/items/batch` | `AdminManageController.java:54,61`；`AdminAuditController.java:63,70,77,83` |
| `AdminOrdersView.vue`（面交单管理） | `/admin/orders` | `GET /admin/orders` | `AdminManageController.java:73` |
| `AdminUsersView.vue`（用户管理：封禁/限权/信用分） | `/admin/users` | `GET /admin/users`、`POST /admin/users/{id}/status`、`POST /admin/users/{id}/credit` | `AdminManageController.java:83,90,96` |
| `AdminLogsView.vue`（操作审计日志） | `/admin/logs` | `GET /admin/audit-logs` | `AdminManageController.java:108` |

### 1.2 平台运营台（`ershou-web/src/views/console/**`，独立顶层路由）

路由定义：`ershou-web/src/router/index.ts:170-215`（父级 `meta.requiresAuth+requiresAdmin`，子路由继承）。

| 页面 | 路由 | 主要接口（后端） | 后端鉴权 |
| --- | --- | --- | --- |
| `PlatformConsoleShell.vue`（独立 Shell） | `/console/platform` | 仅 `GET /schools/{id}/campuses`（公开读，用于校区下拉） | 公开读（非管理接口） |
| `PlatformConsoleOverview.vue`（概览） | `/console/platform`（`console-overview`） | `GET /platform/admin/overview`、`GET /platform/admin/orders` | `PlatformConsoleController.java:63,193` |
| `PlatformConsoleItems.vue`（平台商品） | `/console/platform/items` | `GET /platform/admin/items`、`PUT /platform/admin/items/{id}`、`POST .../offline\|online`、`POST /platform/admin/items`（发布）、`GET /platform/admin/points`、`GET /categories` | `PlatformConsoleController.java:77,85,92,100`；`PlatformAdminController.java:57,96` |
| `PlatformConsolePoints.vue`（代卖点） | `/console/platform/points` | `GET/POST/PUT/DELETE /platform/admin/points`、`POST /platform/admin/points/{id}/status`、`GET /schools/{id}/campuses` | `PlatformAdminController.java:57,64,71,78,86` |
| `PlatformConsoleCoupons.vue`（优惠券） | `/console/platform/coupons` | `GET/POST /platform/admin/coupons`、`PUT /platform/admin/coupons/{id}`、`POST .../status` | `PlatformConsoleController.java:110,117,125,132` |
| `PlatformConsoleFlash.vue`（秒杀场次） | `/console/platform/flash-sales` | `GET/POST /platform/admin/flash-sales`、`PUT /{id}`、`POST /{id}/items`、`DELETE /{id}/items/{itemId}`、`POST /{id}/status`、`GET /platform/admin/items` + `GET /platform/shop/items`（选品） | `PlatformConsoleController.java:142,149,157,165,172,180` |
| `PlatformConsoleOrders.vue`（平台订单） | `/console/platform/orders` | `GET /platform/admin/orders` | `PlatformConsoleController.java:193` |
| `PlatformConsoleCache.vue`（缓存运维） | `/console/platform/cache` | `GET /platform/admin/cache/stats`、`POST /platform/admin/cache/refresh` | `PlatformConsoleController.java:202`；`PlatformAdminController.java:105` |

> 管理接口总计：`module/admin` 3 个控制器 **25 个** + 平台两个管理控制器 **24 个**（17+7）= **49 个**。逐一核对：**49/49 方法内都调用 `AdminAuthService.requireAdminId()`**（grep 证据见"已核实安全项"）。前端 `requiresAuth/requiresAdmin` 只是 UX，权限最终由后端判定。

---

## 二、漏洞表

> 等级口径：P0 可直接越权/接管且无需前置条件；P1 高危（可接管管理员/不可追溯/显著越权）；P2 中危（需要一定条件或影响有限）；P3 加固项/低危。
> 验证状态：`静态确认` = 读到代码证据可复现；`静态推断` = 代码路径成立但未运行验证；`未验证` = 受环境限制。

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| V-01 | **P1** | 管理后台/运营台共用登录入口 `POST /api/v1/auth/sms-code` + `/auth/login` | `ershou-server/src/main/resources/application.yml:103-104`（`ershou.sms.mock: true`、`mock-code: "123456"`）；`module/user/service/UserService.java:65-72`（发码限 10 次/天/号）、`:74`（mock 时验证码固定 123456 并写入 Redis）、`:84-90`（登录只校验 redis 码==123456）；`module/user/controller/AuthController.java:26,33`（与普通用户同一登录口，无管理员区分/二次验证） | 任何知道管理员手机号的人：先调公开的 `sms-code` 拿到固定码 123456（限 10 次/天/号），再 `login` 即获得**该管理员完整会话**（后续 49 个管理接口全部可用）。开发/演示环境默认配置即为人人可登录任意账号。 | 静态确认（代码路径完整，未实际执行登录） | 生产/演示前必须 `ershou.sms.mock=false`（并加启动校验：非 dev profile 时 mock=true 拒绝启动）；管理员登录单独入口 + 二次验证/强密码；验证码一次性且失败计数。 |
| V-02 | **P1** | 平台运营台 24 个管理接口的全部**写操作**（点位/平台商品/券/秒杀/缓存重建） | `module/platform/**` 全模块 grep `AuditLog`/`sys_audit_log` → **0 命中**；`PlatformConsoleController.java:115-127`（新建/编辑券）、`:146-183`（秒杀场次/商品/状态）、`:82-103`（平台商品编辑/上下架）方法体内均无 `auditLogService.record(...)`；`PlatformAdminController.java:62-89`（点位 CRUD）、`:103-121`（缓存重建）同样无审计。对比：`AdminItemService.java:101-105`、`AdminUserService.java:88-92` 等管理后台写操作都有审计 | 运营台可以对**平台官方商品价格/库存、优惠券库存、秒杀场次、代卖点、缓存**做任意变更，但 `sys_audit_log` 里查不到"谁在何时改了什么"。一旦出现价格错标、券超发、商品被下架等事故，无法追溯责任人，也无法自证操作合规。 | 静态确认 | 在平台模块补统一审计：写接口进入 service 后调用 `AuditLogService.record(operatorId, "platform", <action>, ...)`（把 `requireAdminId()` 的返回值传给 service）；重点覆盖：平台商品改价/上下架、券建改启停、场次建改/加删商品/切状态、点位 CRUD、`cache/refresh`。 |
| V-03 | **P1** | 用户管理 `POST /admin/users/{id}/status`（封禁/限权）与管理员判定 | `module/admin/service/AdminAuthService.java:33-41`（`isAdmin` 只判 `userIds` 白名单与 `role_flags & 8`，**不看 `status`/`banUntil`**）；`module/admin/service/AdminUserService.java:84-87`（封禁只 update `status/ban_until`，全仓无 `StpUtil.kickout`/会话删除：grep `kickout\|disable(` → 0 命中）；`application.yml:45,48`（会话 `timeout: 315360000`≈10 年、`active-timeout: -1` 永不活跃过期） | ①**被封禁/限权的运营账号会话继续有效**，且 `isAdmin` 不检查 status → 仍能调用全部 49 个管理接口；②被封禁的普通用户也可继续用旧 token 操作（直到自己退出，最长 10 年）；③"封禁"在心智上是止损手段，实际无法止损。 | 静态确认（Web 侧封禁后无"强制下线"按钮/调用） | `updateStatus` 在 status=3（以及 role_flags 被清除）时踢会话：对目标 user 执行 `StpUtil.kickout(userId)` 并清 `Authorization:login:*`；`AdminAuthService.isAdmin` 增加 `status==1` 与 `banUntil` 校验；管理端"用户管理"加"强制下线"按钮。 |
| V-04 | P2 | 所有管理页面（点击劫持面） | 全仓递归 grep `X-Frame-Options\|Content-Security-Policy\|X-Content-Type-Options\|Strict-Transport-Security`（`ershou-server/src`、`ershou-web/src`、`index.html`、`vite.config.ts`、网关 src）→ **0 命中**；后端只有 `TraceIdFilter` 写 `X-Trace-Id`（`common/filter/TraceIdFilter.java:23-31`）；仓库内无 nginx/反代配置文件，故"部署层是否补齐安全头"**未验证** | 管理后台未禁止被 iframe 嵌套，且缺少 CSP。攻击者可构造透明 iframe 诱导已登录运营点击"审核通过/强制下架/封禁"（部分操作在列表页一键完成，确认弹窗也在被嵌套页面内，同样可被 UI 欺骗），形成点击劫持；CSP 缺失也让任何未来 XSS 无第二道防线。 | 静态确认（未做浏览器验证；部署层配置未知） | 反代/前端服务器统一加响应头：`X-Frame-Options: DENY`（或 `CSP frame-ancestors 'none'`）、`Content-Security-Policy`、`X-Content-Type-Options: nosniff`；管理端所有破坏性操作加二次确认/二次输入原因。 |
| V-05 | P2 | 用户管理（信用分/封禁）与运营分权 | `module/admin/service/AdminUserService.java:95-124`（`adjustCredit` **没有** `Objects.equals(userId, operatorId)` 自操作判断，可给自己 +100/次、无频率限制；`updateStatus` 只在 `:68-70` 拦"不能改自己"，但**可封禁/限权其它运营**）；`AdminAuthService.java:22-41`（只有单一"是否运营"，无角色分级/只读角色） | 任一运营可：给自己刷信用分（信用分影响平台风控口径）、封禁其他运营账号（若被利用可做内部权限打击）、修改任意其他运营数据；没有四眼原则（审核自己的商品/自己封自己人）。 | 静态确认 | ①`adjustCredit` 加自操作拦截 + 单日累计上限；②禁止对 `role_flags & 8` 的目标做封禁/限权（或仅超级管理员可做）；③引入"只读运营/审核员"角色位，敏感操作二次确认并记录；④审核员不能处置自己发布的商品（`ItemReviewService` 校验 sellerId != operatorId）。 |
| V-06 | P2 | 平台运营台 8 个页面的 GET 响应（用户/订单/商品/缓存明细） | 管理接口返回体无缓存控制头：递归 grep `Cache-Control\|no-store\|Pragma`（server/gateway/web）只命中 1 处非管理页 `module/map/controller/AmapPageController.java:250`（地图 HTML），**管理接口 0 命中**；`PlatformConsoleController.java:201-204`（cache/stats 返回 Redis key 明细+库存值）、`:188-195`（平台订单含买家昵称、商品标题） | 管理数据（用户列表含脱敏手机号、订单、Redis 明细）可能被浏览器/中间代理缓存，运营在共用电脑上退出后他人可从历史/磁盘缓存读到；缓存运维页还暴露了 Redis 内部 key 结构。 | 静态推断（未实测缓存行为） | 管理接口统一加 `Cache-Control: no-store, no-cache, must-revalidate`；缓存明细页对 `value` 打码或只显示统计；退出登录时 `clearToken` 已有，另建议关闭浏览器历史缓存策略。 |
| V-07 | P2 | 微服务（user 8081 / item 8082 / order 8083）直连信任 `X-User-Id`（**关联发现，不在本页面前端链路上**） | `ershou-cloud/ershou-cloud-common/.../UserContextInterceptor.java:14-17`（无条件把请求头 `X-User-Id` 写入 ThreadLocal）；`ershou-order-service/.../MeetupOrderController.java:39`（用 `UserContext.requireUserId()` 当买家 id 建单）；`ershou-item-service/.../InnerItemController.java:28,38,48`（`/inner/items` 内部接口**无任何鉴权**：GET 商品快照 + PUT 扣库存）；网关只在经过它时清理伪造头：`ershou-cloud-gateway/.../AuthGlobalFilter.java:30-40` | 若 8081/8082/8083 可被直连（默认绑定 0.0.0.0，本地/局域网演示环境即满足），攻击者可：伪造 `X-User-Id` 读取任意用户资料/以任意买家身份下单、直接调用 `/inner/items/{id}/lock` 扣减库存。**不涉及管理员权限**（管理接口仍在单体，鉴权独立），但属"绕过网关"的身份伪造面。 | 静态确认（服务未运行，未实测） | 服务只监听内网/127.0.0.1 或仅暴露网关；`/inner/**` 加内部签名（网关或服务间 `X-Internal-Token`）+ 拒绝外部来源；生产加 mTLS/服务网格。 |
| V-08 | P3 | 上架审核 WebSocket `/ws/admin/review` | `ershou-web/src/composables/useAdminReviewSocket.ts:73-77`（`?token=<token>` 拼在 URL）；`module/admin/config/AdminReviewWebSocketConfig.java:31-33`（`setAllowedOriginPatterns("*")`）；握手鉴权本身正确：`AdminReviewHandshakeInterceptor.java:44-61`（无 token 401/非运营 403） | token 出现在浏览器历史、Vite/Nginx access log、Referer 中，可被日志读取者复用；`Origin` 全放行使"拿到 token 的任意页面"都能建 WS（token 本身已是最高凭证，风险有限）。 | 静态确认 | token 改走 `Sec-WebSocket-Protocol` 或用一次性 ws-ticket（短 TTL）；`allowedOriginPatterns` 收敛到前端域名。 |
| V-09 | P3 | 操作日志 `GET /admin/audit-logs`（IP 字段来源） | `module/admin/service/AuditLogService.java:66-79`：`currentIp()` 直接取请求头 `X-Forwarded-For` 的**第一个值**，未校验代理链 | 审计日志中的 IP 可由调用方伪造（只有运营能触发审计写入，但恶意/被诱导运营可伪造来源 IP 做误导），降低审计可信度。 | 静态确认 | 只信任自己反代写入的 `X-Real-IP`/最后一段可信代理；或在 Nginx 强制覆盖 `X-Forwarded-For`；日志同时记录 `user-agent`。 |
| V-10 | P3 | 审计覆盖缺口（非运营台） | `AdminManageController.java:41-46`（`cleanup-empty` 直接调 service，无 `auditLogService`）；`AdminReviewController.java:107-111`（`backfill` 无审计）；`SearchController.java:41`（`reindex` 有 admin 校验但无审计）；`module/user` grep `AuditLog` → 0 命中（登录/登出/SSO 事件不入审计） | 运营手动清空点位、补登记审核队列、重建 ES 索引等"写/批量"动作无留痕；登录成功/失败、验证码发送等认证事件完全无审计，无法发现暴力尝试与异常登录。 | 静态确认 | 以上动作补 `sys_audit_log`；新增"登录/登出/发码"审计事件（可单独表或 sys_audit_log module=auth），并接入告警。 |
| V-11 | P3 | 审核工作台批量审核 `POST /admin/audit/items/batch` | `module/admin/dto/BatchItemAuditDTO.java:12-13`（`ids` 只有 `@NotEmpty`，**无 `@Size` 上限**）；`AdminItemService.java:161-181`（for 循环逐条 `selectById`+条件更新，每条网络往返，整个方法一个事务） | 运营（或窃取运营 token 的攻击者）一次提交超长 ids 列表 → 长事务、大量 DB 往返、连接池占用（对管理员自身能力的 DoS）；对比上架审核批量有限制（`ReviewProperties.java:20` max-batch-size=50，`ItemReviewService.java:306-308` 校验）。 | 静态确认 | `BatchItemAuditDTO.ids` 加 `@Size(max=50)` + service 端兜底；改为 `IN` 批量查询后逐条条件更新。 |
| V-12 | P3 | 强制下架/驳回/封禁/信用分调整的 `reason` 字段 | `module/admin/dto/ReasonDTO.java:9`（仅 `@NotBlank`，无 `@Size`）；`ItemRejectDTO.java:7-9`（无任何校验，service 拼装后写入）；`UserStatusDTO.java:8-13`（`reason` 无长度限制）；`sql/schema.sql:140`（`item.audit_reason VARCHAR(255)`） | 超过 255 字的驳回/下架原因 → MyBatis 更新抛 `Data too long` → 兜底 90001（操作失败但语义不清）；超长内容还会进入 `sys_audit_log.after_value` JSON，造成审计表膨胀。 | 静态确认 | 各 `reason` 加 `@Size(max=200)` 并在 service 截断；审核原因模板本身已限长，补充说明也限长即可。 |
| V-13 | P3 | 网关（运营台统一入口 8088） | `ershou-cloud/ershou-cloud-gateway/.../CorsConfig.java:22-28`（`allowedOriginPatterns=*`、`allowedHeader=*`、`allowCredentials=false`）；`PublicPaths.java:27-29,43-45`（`/api/v1/auth/**` 全公开、`/api/v1/platform/shop/` GET 公开） | 当前因 token 走 `Authorization` 头（非 Cookie）、`allowCredentials=false`，**CSRF 不可利用**；但任意站点可发起跨域读公开接口与预检，生产部署前应收敛。 | 静态确认（CSRF 不可利用已确认） | 生产收敛 `allowedOriginPatterns` 到前端域名；网关对 `/api/v1/platform/admin/**` 增加"必须 admin"的粗粒度校验（纵深防御，免得单点依赖单体校验）。 |
| V-14 | P3 | 上架审核 VO `sellerPhone` 字段 | `module/admin/vo/ItemReviewVO.java:31`（声明 `sellerPhone`），但 `ItemReviewService.java` 全文 grep `setSellerPhone` → 0 命中（从不赋值） | 不构成泄露（恒为 null）；但字段语义误导后续开发者（以为已脱敏下发），且前端类型里有该字段。 | 静态确认 | 删除该字段或按 `AdminItemVO.sellerPhone` 同样走 `maskPhone()`；同时统一 VO 脱敏策略。 |
| V-15 | P3 | 用户管理用户列表 `GET /admin/users` | `module/admin/vo/AdminUserVO.java:23,29,30`（下发 `riskScore`、`deviceId`、`deviceAccountCount`）；`AdminViewAssembler.java:92-117` 装配 | 设备指纹/风控分属敏感运营数据，管理端列表页即可批量导出（虽然只有 admin 可见，但最小化原则应限制）；若运营账号被接管，攻击者拿到全站设备指纹。 | 静态确认 | 列表接口只下发必要字段，`deviceId` 脱敏（如保留前后 4 位）或挪到详情接口并按需审计访问。 |
| V-16 | P3 | 管理端 token 存储与 XSS 面 | `ershou-web/src/utils/request.ts:35-46`（token 存 `localStorage['ershou_token']`）；全站 `v-html` 仅 1 处：`components/ItemCard.vue:124-125`，且后端 ES highlight 已 `"encoder":"html"` 转义（`module/search/service/ItemSearchService.java:137-145`） | 管理端与商城同源共用 localStorage：一旦出现任意 XSS，管理员 token 即被窃取。当前审计：**管理端页面无 `v-html`，唯一 v-html 已由 ES 侧 HTML 转义，未发现可利用的存储型 XSS**（保持关注即可）。 | 静态确认（XSS 面已验证收敛） | 管理端可选 `httpOnly` Cookie（配合 CSRF token）或内存态 token；对 `v-html` 加 code review 红线；上线前跑一次 XSS 扫描。 |
| V-17 | P3 | 管理接口限流 | `Module/admin`/`platform` 管理接口均无限流注解/网关限流规则（`ershou-cloud-gateway/.../SentinelGatewayConfig` 只对 `/api/v1/items` 限流，见设计 4.5）；`PlatformAdminController.java:103-121` 的 `cache/refresh` 会遍历所有校区重建 4 类缓存（重活） | 运营 token 泄露/被诱导时可高频调用 `cache/refresh` 等重接口放大资源消耗；无速率限制也让"误操作脚本"造成雪崩。 | 静态确认 | 对管理写接口（尤其 cache/refresh、batch、backfill）加网关或拦截器级限流；cache/refresh 加最小间隔（如 30s）与幂等锁。 |
| V-18 | P3 | 会话/CSRF 配置加固 | `application.yml:33-54`（sa-token 未显式设置 `is-read-cookie:false`；Sa-Token 默认从 Cookie 读 token）；当前服务端无任何 `Set-Cookie` 写 token 的路径（grep `setCookie`/`Cookie` → 0 命中），前端只用 `Authorization: Bearer`（`utils/request.ts:35-46,71-77`） | 目前 **CSRF 不可利用**（无 Cookie 态）；但默认读 Cookie 会让未来任何 `Set-Cookie` 改动引入 CSRF 风险，属配置硬化项。 | 静态确认（CSRF 已验证不可利用） | 显式 `sa-token.is-read-cookie: false`；管理接口保持"仅 Authorization 头"；如未来用 Cookie 则加 CSRF token。 |

---

## 三、Top 3

1. **V-01｜固定验证码 + 管理员与普通用户同一登录入口 → 管理员账号可被接管（P1）**
   证据：`application.yml:103-104` mock=true/123456；`UserService.java:74,84-90`；`AuthController.java:26,33`。知道管理员手机号 → 公网可调 `sms-code` 拿固定码 → `login` 得到管理员会话 → 49 个管理接口全部可用。**这是唯一能"升级为管理员"的路径，必须最先修**（生产/演示前 `ershou.sms.mock=false` + 管理员二次验证）。
2. **V-02｜平台运营台 24 个管理接口的写操作完全没有审计（P1）**
   证据：`module/platform/**` grep `AuditLog` → 0；`PlatformConsoleController.java:115-183`、`PlatformAdminController.java:62-121` 均无 `auditLogService.record`。运营台能改平台商品价格/库存、券库存、秒杀场次、代卖点、缓存，但事故后查不到操作人。**修复成本低、收益最高**（照抄 admin 模块的审计写法）。
3. **V-03｜封禁/限权不吊销会话，且管理员判定不看账号状态（P1）**
   证据：`AdminAuthService.java:33-41` 不校验 status；`AdminUserService.java:84-87` 只写状态不踢下线（全仓无 kickout）；`application.yml:45,48` token 10 年 + 无活跃超时。被封禁的运营仍能调用全部管理接口；"封禁"无法止损。修法：`kickout` + `isAdmin` 校验 status/banUntil。

---

## 四、已核实的安全控制（正面结论，均带证据）

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | **管理接口鉴权覆盖 49/49，无"漏鉴权/仅登录即可用"的接口**：`module/admin` 25 个（AdminAudit 10、AdminManage 8、AdminReview 7）+ 平台管理 24 个（PlatformConsole 17、PlatformAdmin 7）逐个方法首行/实参调用 `requireAdminId()` | `AdminAuditController.java:46,56,63,70,77,83,97,105,112,119`；`AdminManageController.java:43,54,61,73,83,90,96,108`；`AdminReviewController.java:44,71,78,86,94,102,109`；`PlatformConsoleController.java:63,77,85,92,100,110,117,125,132,142,149,157,165,172,180,193,202`；`PlatformAdminController.java:57,64,71,78,86,96,105` |
| 2 | 非管理员/未登录会被明确拒绝：`requireAdminId` 先 `checkLogin()`（未登录→10002），再 `isAdmin` 失败抛 `NO_PERMISSION(10003)`；admin 路径不在 Sa-Token 白名单 | `AdminAuthService.java:44-51`；`common/config/SaTokenConfig.java:15-29`（白名单 :17-27 不含 `/api/v1/admin/**`、`/api/v1/platform/admin/**`） |
| 3 | **未发现 role_flags/identity_flag 可被请求参数或前端改写**：全仓 `setRoleFlags/setIdentityFlag` 只出现在注册默认值（`:102-103`）与 VO 装配；`UserProfileUpdateDTO` 只有 nickname/avatar/schoolId/campusId/shopName/shopIntro（`module/user/dto/UserProfileUpdateDTO.java:18-31`）；管理端 DTO 也无这些字段 | `module/user/service/UserService.java:102-103`；管理 DTO 见第二节证据 |
| 4 | **未发现可利用的 XSS**：管理后台/运营台页面全部用插值（grep `v-html` 在 `views/admin/**`、`views/console/**` → 0 命中）；全站唯一 `v-html` 在 `ItemCard.vue:124-125`，后端 ES highlight 已 `encoder: html` 转义（`ItemSearchService.java:137-145`）；审计日志 JSON 用 `{{ formatJson(...) }}` 插值（`AdminLogsView.vue:240-245,286-288`） | 同左 |
| 5 | **CSRF 不可利用**：认证凭证只走 `Authorization: Bearer` 请求头（localStorage，`utils/request.ts:35-46,71-77`），服务端无写 Cookie 路径（grep 0 命中）；网关 CORS `allowCredentials=false`（`CorsConfig.java:27`） | 同左 |
| 6 | 敏感信息最小化：管理端手机号统一 AES 解密后 `mask()` 脱敏；审核队列 VO 从不赋值 sellerPhone（无泄露） | `AdminViewAssembler.java:97,162,234-243`；`ItemReviewVO.java:31` + `ItemReviewService` 无 `setSellerPhone` |
| 7 | 无数据导出接口（grep `export\|download` 在 `module/admin`、`module/platform` → 0 命中），不存在批量导出用户/订单数据的后门 | grep 结果 |
| 8 | 审计日志 API **不可篡改**：`sys_audit_log` 只有 insert + 查询（`AuditLogService.record`、`AdminLogService.page`），Mapper 仅继承 `BaseMapper` 且无 update/delete 接口；无删除/编辑日志的 HTTP 接口 | `module/admin/service/AuditLogService.java:32-53`；`AdminLogService.java:40-68`；`SysAuditLogMapper` 仅 `BaseMapper` |
| 9 | 分页有上限（防深分页拖库）：admin 各列表 `MAX_PAGE_SIZE=100` 并对 pageNum 下限兜底；平台订单同样 clamp | `AdminItemService.java:81-83`；`AdminUserService.java:61-63`；`AdminLogService.java:62-63`；`ItemReviewService.java:70,380-381`；`PlatformConsoleService.java:139-148` |
| 10 | 平台缓存统计用 `SCAN` 而非 `KEYS`（不阻塞 Redis），且为只读 | `PlatformConsoleService.java:158-196` |
| 11 | 批量处置有上限（上架审核）：`max-batch-size=50` 且 service 校验 | `application.yml:120`；`ItemReviewService.java:306-308` |
| 12 | 管理端所有写操作（admin 模块）都写了审计日志；强制下架/驳回原因必填；条件更新防并发重复处置 | `AdminItemService.java:86-207`（`auditLogService.record` 于 :101,:141,:182,:204）；`ReasonDTO.java:9`；`ItemMapper.java:42-54`（WHERE status 条件） |
| 13 | 运营台路由有前端守卫（`requiresAdmin`）+ 后端权威校验，前端隐藏不作为唯一防线 | `router/index.ts:170-215`（父 meta）、`:231-251`（守卫）；管理接口 49/49 后端校验 |

---

## 五、未验证 / 不确定

1. **运行时只读验证未执行**：审计时 8080/8088/8081/8082/8083 全部未监听（`Get-NetTCPConnection` 实测），按纪律未启动服务；因此"无 token→10002、普通用户 token→10003、admin token→200"这三条**只有静态代码证据，未发真实请求**。
2. **V-01 未实际执行登录**（未用固定码登录管理员账号，避免产生会话/影响环境）；代码路径静态完整。
3. **V-04 点击劫持未做浏览器验证**（未起 5173/未截图 iframe 场景）。
4. **V-06 缓存行为未实测**（浏览器/代理是否真的缓存了管理响应取决于部署反代，代码里确无 Cache-Control 头）。
5. **V-07 微服务直连未实测**（服务未启动；端口默认监听行为按 Spring Boot 默认 0.0.0.0 推断）。
6. **DB 层审计完整性**：`sys_audit_log` 无触发器/DB 权限约束，拥有数据库账号者可改审计记录——属运维层风险，未验证 DB 账号权限矩阵。
7. **网关 Sentinel/限流实际规则**未逐条运行验证（仅静态看到对 `/api/v1/items` 有规则，管理接口无）。
8. 未审计 `ershou_app` 移动端（本任务范围是 Web 管理页面）；未审计 admin 之外的模块（chat/map/item 等）。

---

## 六、修复建议优先级（给 Lead 排期）

| 优先级 | 动作 | 对应 |
| --- | --- | --- |
| 1（演示/上线前必须） | `ershou.sms.mock=false`，管理员登录独立入口 + 二次验证；非 dev profile 下 mock=true 直接拒绝启动 | V-01 |
| 2 | 平台运营台全部写接口补 `sys_audit_log`（platform 模块统一审计入口） | V-02 |
| 3 | 封禁/限权时 `StpUtil.kickout` 目标用户；`isAdmin` 增加 status/banUntil 校验 | V-03 |
| 4 | 反代/前端加安全响应头（X-Frame-Options/CSP/nosniff）；管理 GET 加 `Cache-Control: no-store`；破坏性操作二次确认 | V-04、V-06 |
| 5 | 管理员自操作/互相封禁/自审商品加限制；引入只读运营角色 | V-05 |
| 6 | 微服务网络隔离 + `/inner/**` 内部鉴权 | V-07 |
| 7 | 其余 P3：WS ticket、审计 IP 可信化、审计覆盖缺口、批量上限、reason 限长、CORS 收敛、设备指纹脱敏、管理限流、`is-read-cookie:false` | V-08~V-18 |

---

## 七、审计范围外的关联提醒（不占本报告主结论）

- 本次审计只覆盖 Web 管理后台/运营台与其后端管理接口；`chat/map/item/order` 等学生端接口的鉴权与 XSS 由对应模块负责人/其他审计成员覆盖。
- 第 5 轮微服务化带来的"网关之外直连服务"风险（V-07）影响面跨模块，建议由 Lead 在集成验收时统一加网络/内部鉴权约束，而不只修某一个服务。

---

*报告生成：只读审计；所有证据行号基于 2026-10-21 当前工作区代码（commit 状态未变）。*
