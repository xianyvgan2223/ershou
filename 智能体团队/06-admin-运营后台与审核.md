# 运营后台与审核 · admin-member 记录

| 项 | 值 |
| --- | --- |
| 成员 | `admin-member` |
| 负责功能 | 运营后台（审核工作台 + WebSocket 实时推送、用户/商品/订单/日志、强制下架）、AI 机审落库、定时任务 5 个、通用基建 |
| 记录文件 | `智能体团队/06-admin-运营后台与审核.md` |
| 本轮状态 | `已完成` |
| 更新时间 | `2026-10-14 12:37` |
| 覆盖功能编号 | 功能清单 #14（定时任务）、#16（通用基建）、#18（运营后台）、#20/#21（上架审核工作台），问题 #4（AI 审核未落库） |

## 1. 本轮状态

- 状态：**已完成**（第 1 轮只读核实，未改任何源码/配置/脚本/SQL/文档，只写本文件）。
- 共享任务：`task-6`（已 claim → 已 complete）。
- 本轮方式：**纯静态阅读 + `git diff`/`grep`**；未启动后端/前端，未运行 `scripts/admin-smoke-test.ps1`、`review-smoke-test.ps1`、`verify-admin-*.mjs`、`probe-admin-review-ws.mjs`（这些都需要运行中的服务，按要求跳过）。
- 覆盖：`module/admin/**`（3 个控制器 + 8 个 service + ws/config/dto/vo/entity/mapper）、`module/ai/**`、`module/system/task/ScheduledTasks.java`、`common/**`、`ershou-web/src/{views/admin,stores/admin.ts,composables/useAdminReviewSocket.ts,api/admin.ts,router/index.ts}`、`sql/{schema.sql,migration-item-review.sql}`。
- **P0 结论：本轮未发现管理员接口越权/未鉴权**（25 个 admin 接口逐个带鉴权证据，见第 4 节 #1）。因此没有触发"立即上报 P0"的通道；无 P0 项记入第 5 节。

## 2. 功能边界

- **做什么**：
  - 运营后台 Web 端（`/admin/*` 7 个页面）：概览、审核工作台（status=1 商品 + 点位）、上架审核、商品管理（强制下架）、面交单查询、用户管理（封禁/限权/信用分）、操作日志。
  - 后端 `module/admin/**`：管理员判定（`AdminAuthService`）、审核/处置/查询/审计留痕。
  - 上架审核队列 `item_review`：入队/出队、列表、统计、批量处置、存量补登记，以及 `/ws/admin/review` 实时推送（本机直推 + Redis 跨实例广播）。
  - AI 机审的**落库与留痕**（`module/ai/**` 的调用方与结果去向）；定时任务 5 个；`common/**` 通用基建（统一返回体、错误码、全局异常、TraceId、JSON 读缓存）。
- **不做什么**（越界即不算我的结论）：
  - 商品发布/编辑/搜索/地图/面交/私聊**自身的业务逻辑**（分属 item/map/order/chat-member），我只核实"它们与审核队列/后台的接口处"。
  - Flutter 端后台（不存在：移动端无后台页面）。
  - 第 2 轮起的修复实施：本轮一律不改代码，只给建议修法。
  - 信用分 `+2`（成交加分）路径是否写 `credit_log` 属 order-member 的 #3，我只写后台人工调整路径。

## 3. 代码地图

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 后台接口（审核工作台） | `ershou-server/src/main/java/com/ershou/campus/module/admin/controller/AdminAuditController.java:34` | `@RequestMapping("/api/v1/admin")`；端点注解行 dashboard:44、audit/items:51、approve:61、reject:68、batch:75、reject-reasons:81、audit/points:90、point approve:103、point reject:110、point status:117（各自的 `requireAdminId()` 调用行见第 4 节 #1） |
| 后台接口（管理与审计） | `.../admin/controller/AdminManageController.java:29` | 端点注解行 cleanup-empty:41、items:48、**items/{id}/offline 强制下架:59**、orders:66、users:78、users/{id}/status:88、users/{id}/credit:94、audit-logs:100 |
| 后台接口（上架审核） | `.../admin/controller/AdminReviewController.java:30` | `@RequestMapping("/api/v1/admin/review")`；端点注解行 feed:38、stats:69、detail:76、reviewed:83、offline:91、batch:99、backfill:107；`parseId` 容错:56 |
| 权限判定（全站唯一入口） | `.../admin/service/AdminAuthService.java:44` | `requireAdminId()` = `StpUtil.checkLogin()` + `isAdmin`；`isAdmin`:22（白名单/roleFlags） |
| 权限配置 | `.../admin/config/AdminProperties.java:20`、`:23` | `userIds` 白名单、`roleFlag=8`；值来自 `application.yml:100-104`（`ERSHOU_ADMIN_USER_IDS`） |
| Sa-Token 拦截 | `.../common/config/SaTokenConfig.java:15` | `/api/**` 统一 `checkLogin`，白名单在 :17-24；**admin 路径不在白名单** |
| 审核 WS 注册 | `.../admin/config/AdminReviewWebSocketConfig.java:31` | `/ws/admin/review` + 握手拦截器 + `setAllowedOriginPatterns("*")`:33；`realtime-enabled=false` 时整体不注册:28 |
| 审核 WS 鉴权 | `.../admin/ws/AdminReviewHandshakeInterceptor.java:44`、`:56` | token→`getLoginIdByToken`→`isAdmin`；非运营 **403**，无 token/无效 **401** |
| 审核 WS 处理 | `.../admin/ws/AdminReviewWebSocketHandler.java:41` | 建连推 `ready`（userId/instanceId/onlineReviewers）；`ping`→`pong`:66 |
| 审核推送 | `.../admin/service/ReviewPushService.java:54`、`:89`、`:112`、`:126`、`:147` | 本机 session 表、`broadcast`、Redis `convertAndSend`、跨实例回推（instanceId 去回声）、在线数=本机∪Redis |
| Redis 频道/键 | `.../common/util/RedisKeys.java:137`、`:142`、`:132` | `ershou:admin:review:channel`（Pub/Sub）、`ershou:admin:review:online`（Hash，TTL 90s）、`ershou:admin:dashboard` |
| Redis 订阅 | `.../admin/config/AdminReviewRedisConfig.java:21` | 订阅频道 → `onRemoteMessage` |
| 审核队列服务 | `.../admin/service/ItemReviewService.java:104`、`:123`、`:144`、`:224`、`:253`、`:292`、`:377`、`:452`、`:529`、`:548` | 入队、改名重入队、enqueue 统一实现、已审核、下架、批量、列表、统计、待审数、补登记 |
| 队列实体/表 | `.../admin/entity/ItemReview.java:22`、`:27`、`:38`；`sql/migration-item-review.sql:17`；`sql/schema.sql:459` | STATUS_PENDING/DONE、TYPE_*、ACTION_*；`uk_item(item_id)` 唯一键 |
| 队列入队调用点 | `.../item/service/ItemService.java:346`、`:454`、`:532`、`:574`；`.../admin/service/AdminItemService.java:99`；`ItemReviewBackfillRunner.java:35` | 发布在售、在售改名/改描述、重新上架、违规二次申请通知、审核通过、启动补登记 |
| 商品状态条件更新 SQL | `.../item/mapper/ItemMapper.java:42`、`:47`、`:52`、`:63`、`:68`、`:85` | approveAudit/rejectAudit（`WHERE status=1`）、forceOffline（`status IN (1,2,3,6)`）、applyRelist、markLiveSnapshot、downlist（→5） |
| 商品审核工作台服务 | `.../admin/service/AdminItemService.java:87`、`:130`、`:150`、`:191` | 通过（:99 入上架审核队列）、驳回、批量（**:172-177 未入队**）、强制下架 |
| 用户管理 | `.../admin/service/AdminUserService.java:67`、`:96` | updateStatus（自操作拦截:68、封禁写 status/ban_until:84）、adjustCredit（:107 改分、:109-117 **写 credit_log**） |
| 概览统计 | `.../admin/service/AdminOverviewService.java:46` | 14 个 count(*) + 30s 读缓存；缓存失效在 `AuditLogService.java:48` |
| 审计留痕 | `.../admin/service/AuditLogService.java:32`；`.../admin/service/AdminLogService.java:40`；`sql/schema.sql:438` | 写 `sys_audit_log`、日志页查询（时间参数宽松解析:77） |
| 视图装配（脱敏） | `.../admin/service/AdminViewAssembler.java:97`、`:162`、`:234` | 用户/商品列表手机号一律 `phoneCrypto.decrypt` → `mask` |
| AI 机审 | `.../module/ai/KeywordContentAuditService.java:26`、`:38`、`:50`；`AuditResult.java:5` | 禁售词 BLOCK(0.95)/可疑词 REVIEW(0.7)/否则 PASS(0.05)；结果含 decision/score/labels/reason |
| AI 结果去向 | `.../item/service/ItemService.java:301`、`:329`、`:391`；`sql/schema.sql:392` | 只写 `item.audit_result/audit_reason` 与日志；`ai_audit_record` **全仓仅 docs + schema 出现** |
| 定时任务 | `.../module/system/task/ScheduledTasks.java:58`、`:96`、`:127`、`:169`、`:234` | 锁库释放每分钟、意向失效每 5 分钟、商品过期每小时:30、浏览量回写每 10 分钟、空点位清理每天 03:10 |
| 调度开关 | `.../CampusApplication.java:12` | `@EnableScheduling`；无 ShedLock/XXL-Job/Quartz 依赖（`pom.xml` grep 无命中） |
| 额外定时任务 | `.../search/service/ItemIndexService.java:100`（ES 每 2 分钟）、`.../admin/service/ReviewPushService.java:183`（WS 僵尸连接清理 60s） | 共 7 个 `@Scheduled`，其中 5 个在 `ScheduledTasks` |
| 通用基建 | `.../common/result/Result.java:8`、`ResultCode.java:7`、`GlobalExceptionHandler.java:18`、`TraceIdFilter.java:16`、`JsonCache.java:147`、`CacheProperties.java:45` | `{code,message,data,traceId}`；错误码分段；异常兜底 90001；MDC+响应头/响应体 traceId；防击穿读缓存 |
| 后台前端路由 | `ershou-web/src/router/index.ts:104`、`:167-189` | `/admin` 子路由 7 个，`meta.requiresAdmin`；守卫 :174-186 非运营跳首页 |
| 后台前端 API | `ershou-web/src/api/admin.ts:166`、`:248`、`:271`、`:370`、`:395`、`:403` | 共 24 个封装函数 = 老后台 17 个（:166-288，含强制下架 :248、改信用分 :271）+ 上架审核 7 个（:370 feed / :376 stats / :380 detail / :385 reviewed / :390 offline / :395 batch / :403 backfill）；`功能清单.md:280` 的"17 个"是上架审核页之前的旧数字 |
| 实时推送前端 | `ershou-web/src/composables/useAdminReviewSocket.ts:73`、`:79`、`:107`、`:137` | `ws://host/ws/admin/review?token&nickname`；心跳 15s+看门狗；退避重连 8 次；`ready`→回调重拉列表 |
| 上架审核页 | `ershou-web/src/views/admin/AdminReviewView.vue:70`、`:376`、`:735` | socket 接入与事件处理、`onMounted`（:378 `socket.connect()`）、下架弹窗（原因选填占位文案:743） |
| 后台外壳 | `ershou-web/src/views/admin/AdminShell.vue:22`、`:49`、`:71` | 侧栏 7 项；上架审核角标单独拉 `/review/stats`；手动刷新 `reloadTodo` |
| 概览 store | `ershou-web/src/stores/admin.ts:24`、`:32`、`:53` | 待办=待审商品+待审点位；2s 复用窗口；写操作后 `refresh()` |
| 强制下架入口（学生端详情页） | `ershou-web/src/views/ItemDetailView.vue:431`、`:192-221` | 仅 `userStore.isAdmin` 显示；原因必填校验:204 |
| 开发代理 | `ershou-web/vite.config.ts:44`、`:49` | `/api` 与 `/ws`（ws:true）都代理到 8080 |
| 只读参考脚本 | `scripts/admin-smoke-test.ps1`、`review-smoke-test.ps1`、`verify-admin-console.mjs`、`verify-admin-review.mjs`（:1-12 覆盖 B1-B8）、`verify-admin-toast.mjs`、`probe-admin-review-ws.mjs`（:1-13 四件事） | 本轮**未运行**，仅确认存在与用途 |

## 4. 已核实结论

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | **25 个 admin 接口逐个都调了 `requireAdminId()`，没有"只靠前端隐藏"的接口**。AdminAudit 10 个方法：:46/:56/:63/:70/:77/:83/:97/:105/:112/:119；AdminManage 8 个：:43/:54/:61/:73/:83/:90/:96/:108；AdminReview 7 个：:44/:71/:78/:86/:94/:102/:109。`rejectItem:69`、`batchItems:76`、`updateUserStatus:89`、`adjustCredit:95` 虽把 `requireAdminId()` 写在实参位置，Java 实参先求值，仍是先鉴权后执行。另：全仓只有这 3 个类映射 `/api/v1/admin`（grep 仅 3 处命中）。 | 见"位置"列行号；`AdminAuthService.java:44-51`；`SaTokenConfig.java:15-25`（`/api/**` 未登录一律 10002，admin 不在 :17-24 白名单） |
| 2 | **管理员判定只有两个来源**：`adminProperties.getUserIds().contains(id)` 或 `sys_user.role_flags & 8`；`application.yml:104` 默认 `${ERSHOU_ADMIN_USER_IDS:}`（未设环境变量时为空串，其绑定结果本轮未运行验证，见第 6 节 #10），普通用户 `role_flags` 注册即 1（`UserService.java:102`），且**没有任何接口能改 `role_flags`**（全仓 `roleFlags` 写入仅 `UserService.java:102`）→ 不存在"自己把自己提成运营"的越权路径。 | `AdminAuthService.java:22-41`；`AdminProperties.java:20-23`；`application.yml:100-104`；`UserService.java:102`；`sql/schema.sql:79` |
| 3 | **`/ws/**` 不在 Sa-Token 拦截范围，审核 WS 自己做了"登录 + 运营"两道校验**：无 token→401、token 无效→401、非运营→**403**（`isAdmin` 判定），并在 `attributes` 里塞 userId 供 handler 使用；关闭 `ershou.review.realtime-enabled` 时 handler 根本不注册。 | `AdminReviewHandshakeInterceptor.java:39-64`；`AdminReviewWebSocketConfig.java:28-33`；`SaTokenConfig.java:15`（只 match `/api/**`） |
| 4 | **5 个定时任务全部在，cron 与文档一致**：锁库释放 `0 * * * * ?`:58、意向 48h 失效 `0 */5 * * * ?`:96、商品过期 `${ershou.item.expire-cron:0 30 * * * ?}`:127、浏览量回写 `${ershou.cache.view-flush-cron:0 */10 * * * ?}`:169、空点位清理 `0 10 3 * * ?`:234。另有 2 个 `@Scheduled` 不在此类：ES 增量同步（`ItemIndexService.java:100`，每 2 分钟）、审核 WS 僵尸连接清理（`ReviewPushService.java:183`，60s）。 | 行号见左；`功能清单.md:214-224` 与 `README`/`开发文档` 口径一致 |
| 5 | **定时任务没有任何分布式锁**：`pom.xml` 无 shedlock/xxl-job/quartz/elastic-job（grep 无命中），`CampusApplication.java:12` 只开 `@EnableScheduling`；`ScheduledTasks.java:26-28` 类注释自认"多实例部署时需换成 ShedLock 或 XXL-Job（开发文档 7.10），浏览量回写尤其要防重复执行"。逐个幂等性：`releaseExpiredLocks` 用 `releaseLock`（`WHERE status=3`）+ 订单 `WHERE status=2` → 幂等；`expireItems` 用 `WHERE status IN (1,2,3,6)` 条件更新 → 幂等；`cleanupEmptyPoints` 删除按 id → 重复执行第二次影响 0 行（但日志按计算值打印，会虚报条数，见第 5 节 P2-6）；**`expirePendingOrders` 与 `flushViewCounts` 不是幂等安全的**。 | `ScheduledTasks.java:58-88`、`:127-158`、`:96-112`、`:169-222`；`ItemMapper.java:22-24`、`:35-36`；`TradePointService.java:465-493` |
| 6 | **`item_review` 表已落库（两处 DDL）且与实体一致**：`uk_item(item_id)` 保证一个商品一条当前记录，`idx_pending(status,queued_at)`；`status` 0 待审/1 已审核，`review_type` 1-5，`review_action` 1 已审/2 下架，`revision` 每次重新入队 +1；`item` 表补 `last_live_title`/`last_live_desc_hash` 两个快照列。 | `sql/migration-item-review.sql:17-39`、`:46-58`；`sql/schema.sql:459-479`；`ItemReview.java:22-40`；`ItemMapper.java:68-70` |
| 7 | **进出队条件完整闭环**：入队 6 个调用点（发布即在售 → TYPE_NEW；在售改名/改描述 → TYPE_RENAMED；重新上架 5/7→2 → TYPE_RELIST；审核通过 → TYPE_NEW/TYPE_VIOLATION_RELIST；启动补登记 → TYPE_LEGACY）；出队只有两种（`markReviewed` → status=1；`downlist` → 商品置 5 + status=1）。列表/统计统一用 `item_id IN (SELECT id FROM item WHERE status IN (2,3))` 过滤，所以"已售出/已下架/已过期/违规下架/审核中"的商品自动从页面隐去（其待审计入 `stalePending`），两端口径一致。 | `ItemService.java:346`、`:454`、`:532`、`:574`；`AdminItemService.java:99`；`ItemReviewBackfillRunner.java:35`；`ItemReviewService.java:377-397`、`:452-481`、`:529-533`、`:224-245`、`:253-284` |
| 8 | **并发处置靠条件更新兜底，不会双双成功**：`markReviewed` 用 `WHERE item_id=? AND status=0`，0 行抛 `61001 REVIEW_ALREADY_HANDLED`；`downlist` 用 `itemMapper.downlist`（`WHERE status IN (1,2,3,6)`），0 行抛 `61001`；商品审核 `WHERE status=1`，0 行抛 `60001`。前端对 61001/61002 做了"移出列表 + 轻提示"处理而不是报错。 | `ItemReviewService.java:227-237`、`:262-265`；`ItemMapper.java:42-53`、`:85-88`；`AdminItemService.java:92-95`；`ResultCode.java:40-42`；`AdminReviewView.vue:239-248` |
| 9 | **WS 推送通道/协议与前端一致**：频道 `ershou:admin:review:channel`，本机 `localSessions` 直推 + Redis 广播（envelope 带 `instanceId`，回来时相同即丢弃防回声）；事件 `ready/queued/handled/violation-applied/pong`；`queued` 载荷实际字段是 `{pending,itemId,reviewTypeText,onlineReviewers}`（**不含 item VO**，与 handler 注释不符，见第 5 节 P2-1）；前端 `handleFrame` 对这 5 种类型逐一处理，`ready` 时回调 `onReconnected` 重新拉列表（断线期间事件不可补）。 | `RedisKeys.java:137`、`:142`；`ReviewPushService.java:89-136`、`:200-209`；`ItemReviewService.java:198-209`、`:707-717`；`useAdminReviewSocket.ts:115-135`、`:151-159`；`AdminReviewView.vue:70-104` |
| 10 | **"强制下架原因必填"在三层都成立**：DTO `@NotBlank(message="请填写原因")`、`AdminItemsView.vue:131-134` 前端拦截 + `ItemDetailView.vue:204` 弹窗校验；入库时写 `item.audit_reason`（`audit_result=2`）并落 `sys_audit_log`（before/after 含 reason）。可下架范围 `status IN (1,2,3,6)`——审核中/在售/已锁定/审核未通过都能被强制下架。 | `ReasonDTO.java:9`；`AdminManageController.java:60-63`；`AdminItemService.java:191-207`；`ItemMapper.java:52-54`；`ItemDetailView.vue:431` |
| 11 | **问题 #4 成立：AI 机审结果不写 `ai_audit_record`**。`ai_audit_record` 全仓 grep 只命中 `功能清单.md:404/420`、`开发文档.md`（4 处）、需求文档、`sql/schema.sql:392`——**没有任何 Java 代码引用**；机审结果只落到 `item.audit_result`（PASS→1，BLOCK/REVIEW→null）与 `item.audit_reason`，加上 `KeywordContentAuditService` 的 log。`AuditResult` 里的 `score/labels` 计算出来了但**丢弃**，所以"命中标签+置信度"没有留痕，误伤率/申诉都无从统计。 | `ItemService.java:301-308`、`:329-330`、`:391-406`；`KeywordContentAuditService.java:38-54`；`AuditResult.java:5-17`；`sql/schema.sql:392-408`；grep `ai_audit_record` 命中清单如上 |
| 12 | **信用分人工调整确实写 `credit_log`**（后台侧路径闭环）：`AdminUserService.adjustCredit` 先算 `after=max(0,before+delta)`，再 `userMapper.setCredit(userId, after, creditLevelOf(after))` 同步等级，然后插 `credit_log`（user_id/change_value/before_score/after_score/reason/biz_type='admin'/operator_id），再写审计日志；校验 `delta != 0` 且 `Math.abs(delta) <= 100`；未填原因落"运营人工调整"。 | `AdminUserService.java:96-124`、`:126-134`；`CreditLog.java:11-25`；`sql/schema.sql:314` |
| 13 | **统一返回体/错误码/TraceId/缓存 4 项基建都在，且 90001 的来源明确**：`Result{code,message,data,traceId}`（traceId 取自 MDC，响应体里就有）；`ResultCode` 分段 10xxx/20xxx/30xxx/40xxx/50xxx/60xxx/61xxx/70xxx/90xxx；`GlobalExceptionHandler` 对 `BizException` 保留业务码、对未捕获 `Exception` 兜底 `90001 SYSTEM_ERROR`（并 `log.error` 带堆栈）；`TraceIdFilter` 16 位 UUID 写 MDC + `X-Trace-Id` 响应头；`JsonCache` 空值哨兵防穿透 + TTL 抖动防雪崩 + 重建锁轮询防击穿、Redis 异常一律降级未命中。 | `Result.java:8-36`；`ResultCode.java:7-53`；`GlobalExceptionHandler.java:18-22`、`:50-54`；`TraceIdFilter.java:16-31`；`JsonCache.java:59-69`、`:106-128`、`:147-187`、`:227-231`；`CacheProperties.java:19-54` |
| 14 | **工作区未提交改动已核实（未回滚/未提交）**：`git status` 显示 6 改 1 增——`JsonCache.java`/`CacheProperties.java` 是 2026-10-10 的**防击穿修复**（旧实现"抢不到锁→睡 80ms→自己也回源"，`loader` 在锁外执行，50 并发实测约 50 次回源/P95≈500ms；新实现等待方在锁存续期间轮询，新增 `maxRebuildWaitMillis=500`、`rebuildWaitMillis` 80→50，锁 TTL 5s 常量抽成 `REBUILD_LOCK_TTL_SECONDS`）；另改了 `ItemService.java`、`README.md`、`开发文档.md`、`开发日志.md`，未跟踪 `scripts/audit-cache.mjs`、`智能体团队/`。本次核实基于工作区（含未提交改动）版本。 | `git -C E:\ershou status --porcelain`、`git diff --stat`、`git diff -- .../JsonCache.java .../CacheProperties.java` 输出 |
| 15 | **后台看不到用户明文手机号**：用户/商品列表都走 `AdminViewAssembler.maskPhone`（AES 解密后打码），审核队列 `ItemReviewVO.sellerPhone` 虽声明了字段但 `toVO` 从不赋值 → 审核页拿不到卖家手机号（第 5 节 P2-4）。 | `AdminViewAssembler.java:97`、`:162`、`:234-243`；`ItemReviewVO.java:31`；`ItemReviewService.java:623-700`（无 setSellerPhone） |

## 5. 发现的问题

> 分级：P0 阻断/越权 · P1 影响体验或数据 · P2 一致性/文档过期。**本轮无 P0**（见第 1 节 #1 结论）。

| # | 级别 | 现象 | 证据 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- |
| P1-1 | **P1** | **批量审核通过不进「上架审核」队列**：单条通过会 `onItemLiveOptimistic` 入队，批量通过只改状态、不入队。 | `AdminItemService.java:99-100`（单条 approve 入队）vs `AdminItemService.java:172-177`（batch 只 `approveAudit`+`afterItemWrite`，无 `reviewService` 调用） | 破坏"每条上架到在售的商品都被审核员看过"的核心保证：从审核工作台批量通过的「审核中」商品（AI 转人工、改名命中可疑词、违规二次申请）直接变在售且**没有 `item_review` 记录**，审核员永远看不到；`pendingCount` 也不会变，无人察觉。 | 在 `batch` 的 approve 分支里补 `reviewService.onItemLiveOptimistic(id, isViolationRelist(item) ? TYPE_VIOLATION_RELIST : TYPE_NEW)`，与单条 approve 共用同一段逻辑（抽 `private void approveOne(Item item, Long operatorId)`）。**改源码**。 |
| P1-2 | **P1** | **上架审核列表的关键词搜索是在分页之后做内存过滤**，搜不全还会把 `total` 改成"本页过滤后的条数"。 | `ItemReviewService.java:415-426`（注释自述"关键词过滤放在内存里"）、`:448-449`（`total = vos.size()`）；DB 分页在 `:407` 已执行 | 审核员搜一个词，只命中第 1 页里含该词的行；第 2 页及以后的命中结果永远搜不到，`total` 也随页码乱跳 → 关键词检索不可信（审核页的价值就是"别漏"）。 | 要么让关键词进 SQL（`item_review.title` 快照 LIKE 或 join item 按当前标题/描述过滤，分页前过滤），要么明确降级为"仅筛选当前页"并在 UI 标注。**改源码**。 |
| P1-3 | **P1** | **`expirePendingOrders` 用无条件 `updateById` 把"卖家刚接受"的单子改回失效**：任务先 `select` 出 `status=1 且 created_at < now-48h` 的行，再逐条 `updateById` 置 5，不带 `WHERE status=?`。而卖家 `accept` 也是读后写（`:363` 校验后 `:383-386` 无条件置 2）。 | `ScheduledTasks.java:99-108`；`OrderService.java:358-386`；对照同类任务都用了条件更新（`ScheduledTasks.java:145-147`、`ItemMapper.java:22-24`） | 丢失更新：卖家接受了面交（商品已被 `lockStock` 锁成 3），任务把它置 5「已失效」→ 买家看到"刚被接受又失效"，商品却仍锁在该买家名下，直到 `lock_expire_at` 被 `releaseExpiredLocks` 释放才自愈；期间双方无法成交。 | 把更新改成条件更新 `eq(status,1)`，`rows==0` 时跳过并记 debug 日志；顺带核对 `accept` 侧同样加 `eq(status,1)` 条件（属 order-member 范围，需跨成员确认）。**改源码**。 |
| P1-4 | **P1** | **封禁不踢下线，且 `ban_until` 只写不读**：`updateStatus` 只更新 `status/ban_until`，没有 `StpUtil.kickout`（全仓无 kickout/disable 调用）；`ban_until` 除写入与 VO 展示外无任何读取点，也没有"到期自动解封"任务。 | `AdminUserService.java:84-87`（只 set status/ban_until）；grep `kickout\|logout\|disable\(` 仅命中 `AuthController.java:39-41`（用户自助登出）；grep `banUntil\|getBanUntil` 只命中写入与 `AdminViewAssembler.java:109`/`AdminUserVO.java:26` 展示；`ScheduledTasks` 无解封任务；`AdminUsersView.vue:36` 默认 `banDays:7`、`:251-253` 展示"封禁到期" | ①被封禁用户（含**被封禁的运营**）拿旧 token 继续调接口，直到 token 7 天自然过期；②UI 显示"封禁 7 天"但实际永久封禁（`status=3` 不会被自动改回），运营会以为到期自动恢复。 | ①`updateStatus` 在封禁时 `StpUtil.kickout(userId)`；②加一个"解封到期用户"定时任务或把 `ban_until` 纳入登录/鉴权判断（`status=3 && ban_until<now` 视为解封并落审计）。会话强制下线部分属 auth-member #1，需 Leader 裁决归属。**改源码** |
| P1-5 | **P1** | **「审核工作台」（待审 status=1 商品 + 点位 tab）没有任何实时通道**：唯一 WS 只服务「上架审核」页；`violation-applied` 事件也是推到上架审核页，只是弹一句"请到审核工作台处理"。 | 前端 grep `WebSocket\|useAdminReviewSocket\|setInterval\|socket` 在 `views/admin/**` 只命中 `AdminReviewView.vue:15/70/378/411/413`；`ItemReviewService.java:337-348`（事件名 `violation-applied`）；`AdminReviewView.vue:95-98`（收到后仅 `showLongTip`）；`AdminShell.vue:49-62`（角标仅在挂载与手动刷新时拉）；`stores/admin.ts:32-50`（2s 复用窗口，不轮询） | 有 AI 转人工 / 违规二次申请商品时，停在审核工作台页面的运营看不到任何变化，待办角标也不动，必须手动点"刷新待办"或跳页 → 与"审核工作台 + WebSocket 实时推送"的功能口径不符（功能清单 #21 只对上架审核承诺实时）。 | 复用同一通道：让 `violation-applied` 同时带 `pendingAudit`（后端已下发，见 `ItemReviewService.java:343`）并在 `AdminShell`/`AdminAuditView` 消费；或给审核工作台接同一 WS（建议把 `useAdminReviewSocket` 提升为 shell 级订阅）。**改源码** |
| P1-6 | **P1** | **AI 机审无留痕库（功能清单问题 #4 确认成立）**：机审只写 `item.audit_result/audit_reason`，`ai_audit_record` 表建了但无人写；`score/labels` 计算后丢弃。 | 同第 4 节 #11：`ItemService.java:301-308`、`:329-330`、`:391-406`；`AuditResult.java:5-17`；`sql/schema.sql:392-408`；全仓 `ai_audit_record` 仅 docs+schema | 无法统计机审拦截/转人工的量、误伤率，也没有"命中标签+置信度"证据链，用户申诉时无法复现判定；`item.audit_result` 只有 1/null/2，区分不了"机审通过"与"人工通过"。 | 在 `KeywordContentAuditService`（或包一层 `AuditRecordService`）里按 `biz_type='item', biz_id=itemId` 落 `decision(1/2/3)+score+labels(json)+reason+model_version='keyword-v1'`；BLOCK 分支因抛异常回滚，需用 REQUIRES_NEW 或 afterCommit 写（参考 chat 模块 `ChatRiskRecorder` 的做法）。**改源码** |
| P2-1 | P2 | 审核 WS 协议注释与实现不一致：注释写 `queued.data.item` 是 `ItemReviewVO`，实际只推 `{pending,itemId,reviewTypeText,onlineReviewers}`，前端只好再打一次 `fetchReviewDetail`。 | `AdminReviewWebSocketHandler.java:23`（注释）vs `ItemReviewService.java:198-205`（实际载荷）、`useAdminReviewSocket.ts:5-11`、`AdminReviewView.vue:116` | 后续按注释接入的端（如移动端）会拿到 `undefined`；每个新上架事件多一次 REST 往返。 | 改注释与前端类型注释；若要省那次请求，就在 `broadcastQueued` 里带上 VO（注意别把大描述塞进推送）。**改注释（或改代码）** |
| P2-2 | P2 | `ItemReviewService.resetOnRelistApply` 是死代码（全仓无调用方）；实际靠 `enqueue` 对已存在行重置 `status=PENDING` 完成同样的事。 | `ItemReviewService.java:582-594`（定义）；grep `resetOnRelistApply` 仅此一处命中 | 后来者会以为"卖家二次申请会重置审核记录"有专门逻辑，改错地方；注释与真实路径不一致。 | 删除该方法，或在 `ItemService.applyRelist` 里显式调用并补测试。**改源码** |
| P2-3 | P2 | **上架审核「下架」原因口径不一致**：单条下架原因选填（留空用默认文案 `"上架审核：内容不符合规范，已下架"`），批量下架原因必填（service 里校验）；而"强制下架"是必填。 | `ReviewOfflineDTO.java:10-11`（选填）；`ItemReviewService.java:260`（默认文案，常量在 `:78`）、`:298-300`（批量必填）；`AdminReviewView.vue:743`（占位文案"选填"）、`:292`（批量 `inputValidator` 必填）；`ReasonDTO.java:9`（强制下架必填） | 同一页面两种下架留痕质量不同，事后审计看到一批"内容不符合规范"的默认原因，无法区分是谁因为什么下的。 | 统一为必填（单条也加 `@NotBlank` 或前端校验），或至少把默认文案改成"未填写原因（审核页下架）"以便区分。**改源码** |
| P2-4 | P2 | `ItemReviewVO.sellerPhone` 声明了但从不赋值（审核页拿不到卖家联系方式）；同时 `AdminItemVO.sellerPhone` 是有值的（脱敏）。 | `ItemReviewVO.java:31`；`ItemReviewService.java:623-700`（toVO 只 set sellerNickname/sellerCreditScore）；`AdminViewAssembler.java:162` | 前端 `api/admin.ts:309` 的类型里有 `sellerPhone`，审核员会以为"没填过手机号"；不能靠手机号去核对站外引流嫌疑。 | 要么删字段/类型，要么在 `toVO` 里补 `assembler.maskPhone(seller.getPhone())`（保持脱敏）。**改源码** |
| P2-5 | P2 | **点位审核队列结构性恒空**：`trade_point.audit_status` 建表默认 1，代码里唯一写入点是 `setAuditStatus(1)`，用户提点/搜索落库都免审核 → `/admin/audit/points?auditStatus=0` 恒 0 条，概览 `pendingPoints` 恒 0，审核工作台点位"待审"tab 永远显示空。 | `sql/schema.sql:55`（DEFAULT 1）、`:497`（说明默认已通过）；`TradePointService.java:353`（唯一 `setAuditStatus`，值恒 1）；`AdminPointService.java:56-57`（`auditStatus=0` 过滤）、`:96-98`（条件更新 `audit_status=0`）；`AdminOverviewService.java:54-55` | 后台有一个"永远没数据"的页面，运营会怀疑功能坏了；`AdminPointService` 类注释（`:27-28`"用户提交的点位（audit_status=0）在这里通过或驳回"）已过期。 | 二选一：①承认"提点免审核"，去掉点位审核 tab / 概览待审点位卡，只保留启用/停用（推荐）；②改回提点 `audit_status=0` 走审核。**改源码 + 改文档** |
| P2-6 | P2 | **5 个定时任务都没有分布式锁**，`ScheduledTasks` 类注释自认需要 ShedLock/XXL-Job；其中浏览量回写即使单实例也非严格幂等（先写 DB 再扣 Redis 增量，两步之间崩溃/被 kill 会重复累加）。 | `ScheduledTasks.java:26-28`（注释）、`:198-218`（先 `incrViewBy` 后 `decrement`，无水位/去重）；`pom.xml` grep 无 shedlock/quartz/xxl 依赖；`CampusApplication.java:12` | 多实例部署（README 本地单实例，故记 P2）会重复执行：浏览量按倍数虚高，`expirePendingOrders` 竞争放大 P1-3；单实例下进程被杀也会多算浏览量。 | 引入 ShedLock（Redis 或 JDBC provider）给 5 个任务加 `@SchedulerLock`；浏览量改为"先把增量搬到处理中 key 再落库"或用 `item_view_flush:{id}` 幂等键。**改源码 + 加依赖** |
| P2-7 | P2 | **错误码/接口清单/编号等文档已过期，与代码不一致**（四处）：①`功能清单.md:257` 说错误码"共 24 个"且只列到 90xxx，实际 `ResultCode` 有 36 个业务码，还有未记载的 **61xxx（上架审核 61001/61002）** 与 **70xxx（私聊 70001-70007）**；②`功能清单.md:330` 把上架审核接口写成 `/admin/audit/items`，实际是 `/admin/review/feed`、`/items/{id}/reviewed\|offline`、`/batch/{action}`、`/backfill`；③`功能清单.md` 总览表 #16=移动端 App 而正文 `### 16. 通用基建`（`### 20. 移动端 App`、`### 21. 上架审核工作台`），同一文档三套编号；④`sql/schema.sql:380` 注释"上架到期(默认30天)"，代码默认 7 天。 | `ResultCode.java:7-53`；`AdminReviewController.java:30-111`；`功能清单.md:257/280/330`、`:28` vs `:252`、`:307` vs `:325`；`ItemService.java:95-96`（`ershou.item.valid-days:7`）；`sql/schema.sql:380`；`ScheduledTasks.java:115` | 接手者按文档找接口/找错误码会走错路（尤其 61xxx 是前端按码分支处理的：`AdminReviewView.vue:241`）。 | 更新 `功能清单.md`（错误码数量与分段、#21 接口清单、编号自洽）与 `schema.sql:380` 注释。**改文档** |
| P2-8 | P2 | **审核 WS 的 token 走 URL query，且 `setAllowedOriginPatterns("*")`**：token 会进浏览器历史/代理与 Nginx access log；任意 Origin 都能发起握手（前提是攻击者已知 token，浏览器不会自动带 query token，故不是 CSWSH）。 | `useAdminReviewSocket.ts:73-77`（`?token=`）；`AdminReviewHandshakeInterceptor.java:73-88`（query 优先，其次 `Authorization`）；`AdminReviewWebSocketConfig.java:33` | token 泄漏面扩大（日志/Referer/截图）；与私聊同一套模式，属既有设计。 | 生产反代侧关掉 query 记录或改用一次性 `ws-ticket`；至少把 `setAllowedOriginPatterns` 收敛到前端域名白名单。**改配置/源码** |
| P2-9 | P2 | **强制下架对「已下架(5)/已过期(7)」的商品没有出口**，且报错文案漏了 5：SQL 限定 `status IN (1,2,3,6)`，0 行时提示"已售出/已超期/已强制下架的商品无需再下架"（未提"已下架"）。而卖家可把 5/7 一键重新上架成在售。 | `ItemMapper.java:52-53`；`AdminItemService.java:197-199`（文案）；`ItemService.java:511-523`（relist 允许 5/7→2） | 运营发现一件已下架的违规商品无法直接判违规，只能等卖家重新上架后再处置（届时会进审核队列，路径能走通）；文案不全容易让运营以为"下架失败"。 | 文案补"已下架"；若要"违规判定"能力，可允许 5→8（需 product 确认是否影响卖家 relist 权利）。**改源码（低风险）** |
| P2-10 | P2 | 后台侧栏角标非实时：处置完一条后，"上架审核"角标要手动点侧栏底部刷新才更新（`AdminShell` 单独持 `reviewPending`，与页面 `stats` 不互通）。 | `AdminShell.vue:18`、`:49-56`、`:70-74`；`AdminReviewView.vue:231-233`（`applyStats` 只改本页 stats） | 页面实时、角标滞后，两个数字并存时运营不知道该信哪个。 | 把 `reviewPending` 提到 `stores/admin.ts`（或复用 pinia 的 review stats），处置后统一 `refresh()`。**改源码（小改）** |
| P2-11 | P2 | `JsonCache` 收尾两处小瑕：`jitter()` 注释写"±10%"实际只加不减（0~+10%）；`REBUILD_LOCK_TTL` 与 `REBUILD_LOCK_TTL_SECONDS` 是同一语义的两个常量，靠人手工同步；等待方 `isRebuildLocked` 失败时按"未加锁"处理，会与重建方并发回源（降级可接受，但注释未说明）。 | `JsonCache.java:226-231`（`ttl.plusMillis(extra)`，extra≥0）、`:38`、`:41`、`:144`；`:190-197` | 无功能影响（抖动范围与锁语义都安全），属可读性/维护性；注释与行为不符会让后人改错。 | 注释改为"TTL 加 0~10% 抖动"；用 `Duration.ofSeconds(REBUILD_LOCK_TTL_SECONDS)` 只留一个常量。**改源码（纯清理）** |
| P2-12 | P2 | TraceId 不透传上游：`TraceIdFilter` 每次请求都新生成，不读请求头 `X-Trace-Id`；也没有访问日志（只有异常时 `log.error` 带 MDC）。 | `TraceIdFilter.java:23-25`；`GlobalExceptionHandler.java:19`、`:52` | 前端/网关已有链路号时无法串起来（当前单体 + Vite 代理，影响有限）。 | 先取 `X-Trace-Id` 命中则复用，否则生成；如需按 traceId 查请求可加访问日志。**改源码（小改）** |

## 6. 未核实 / 不确定

本轮**只做静态阅读**，以下内容没有验证，**不作为结论**：

1. **所有"实测"数字**：功能清单 `:37` 的"WebSocket 实测 106ms 到达"、`JsonCache` 注释里的"50 并发约 50 次回源/P95≈500ms"、`scripts/*` 里声称的断言数（52 项 / 64 项 / B1-B8）——我**没有运行**任何服务、脚本或压测，这些数字只是转述代码/文档里的自述。
2. **运行期行为**：WS 握手 403/401 的真实表现、Redis Pub/Sub 跨实例去回声、多实例重复执行、`requireAdminId()` 在真实 Sa-Token 会话下的返回码（我读的是 `SaTokenConfig` + `GlobalExceptionHandler` 的映射，未发请求验证）。
3. **数据库真实状态**：`item_review`/`ai_audit_record`/`credit_log` 是否已在目标库里建好、`sql/migration-item-review.sql` 是否跑过、`item.last_live_title` 是否已回填（`ItemReviewService.java:614-621` 的"无快照不判改名"是否会误漏），均未连库确认。
4. **前端构建与渲染**：`AdminReviewView.vue`（992 行）、`AdminAuditView.vue`（981 行）、`AdminItemsView.vue`（673 行）等大页面我**只读了与后台审核相关的片段**（socket 接入、处置流程、下架弹窗、角标），其余模板/样式细节与 Element Plus 版本兼容性未逐行核对，也未跑 `vue-tsc`/构建。
5. **跨模块归属**：`OrderService.accept` 的条件更新补强（P1-3 的一半）、封禁强制下线（P1-4）分别落在 order-member / auth-member 的范围，我只写了"后台侧我看到的路径"，未与他们对齐，也未验证他们是否已在本轮记录里覆盖。
6. **信用分 `+2`（成交加分）是否写 `credit_log`**：属 order-member #3，我**没看** `OrderService` 的加分路径，故本文件只断言"后台人工调整必写"。
7. **权限矩阵的完整性**：我核实的是"admin 控制器都鉴权 + 无其他类映射 `/api/v1/admin`"；未逐个审查**全部**学生端接口是否存在"本该运营才能做、却只要求登录"的能力（例如 `/api/v1/search/reindex` 已确认有 admin 校验，其余未穷举）。
8. **`admin-smoke-test.ps1` 等脚本的断言内容**：只读了 `verify-admin-review.mjs:1-12` 与 `probe-admin-review-ws.mjs:1-35` 的头部说明，未读全文，也未执行。
9. **计划任务 cron 与生产配置的差异**：`ershou.item.expire-cron` 在 `application.yml`/`application-local.yml`/`application-dev.yml` 里都**没有**显式配置（走注解默认 `0 30 * * * ?`）；`ershou.search.sync-cron` 只在 `application-dev.yml:36` 配置（`0 */2 * * * ?`），主 `application.yml` 走注解默认。`application-local.yml`/`application-dev.yml` 我**只 grep 未通读**。
10. **`ershou.admin.user-ids: ${ERSHOU_ADMIN_USER_IDS:}` 在空环境变量下的绑定结果**：空串 → 空 `List<Long>` 还是绑定失败，属 Spring Boot Binder 行为，本轮**未运行验证**（`AdminProperties.java:20` 的字段初始值是 `new ArrayList<>()`，故"未配置时白名单为空"这一结论方向是安全的，但严格说未跑过）。

## 7. 下一轮计划

1. **修 P1-1（批量通过不入队）**：`AdminItemService.batch` approve 分支补 `onItemLiveOptimistic`，抽公共方法给单条/批量共用；验收：批量通过一条 status=1 的商品后 `item_review` 出现 pending 行且 `/admin/review/stats.pending` +1。**需要改源码**（唯一写者建议仍是我，写 `AdminItemService.java`）。
2. **修 P1-3（任务与接受的丢失更新）**：`ScheduledTasks.expirePendingOrders` 改条件更新 `eq(status,1)`；与 order-member 确认 `accept` 侧是否同样加条件；验收：并发脚本验证"刚接受不被失效"。**需要改源码 + 跨成员协调**。
3. **修 P1-2（关键词搜索）**：把关键词过滤下推到 SQL（分页前），或明确降级并在 UI 标注；验收：3 页数据里第 2 页的命中项能被搜到。**需要改源码**。
4. **落 `ai_audit_record`（P1-6 / 问题 #4）**：新增写入服务 + REQUIRES_NEW/afterCommit 保证 BLOCK 路径也留痕；验收：发布命中禁售词/可疑词的用例后 `ai_audit_record` 各有一条 decision=2/3。**需要改源码 + 建表确认**（表已存在，见 `sql/schema.sql:392`）。
5. **文档修正（P2-7，零风险先做）**：更新 `功能清单.md`（错误码数量与 61xxx/70xxx 分段、#21 接口清单、#16/#20 编号自洽）、`schema.sql:380` 注释、`ItemReviewService` 类注释与 `AdminPointService:27-28` 注释；以及删/接 `resetOnRelistApply`（P2-2）。**需要改文档/源码**。
6. **优先级建议**：P0 无；建议第 2 轮排期顺序 P1-1 → P1-3 → P1-6 → P1-2 → P1-4/P1-5，P2 批量文档修正与 `JsonCache` 清理可并行。

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-14 | 收到 | Leader 指派 T6：第 1 轮只读核实，唯一可写文件 `智能体团队/06-admin-运营后台与审核.md`，禁止改源码/配置/脚本、禁止启服务与跑冒烟脚本；重点是后台鉴权、5 个定时任务、审核队列进出条件、问题 #4、通用基建 |
| 2026-10-14 | 提交 | 回报（≤15 行）：状态已完成 / Top 发现（批量通过不入队、AI 不落库、任务丢失更新、封禁不踢下线不自动解封、关键词搜索分页后过滤）/ 无 P0 / 建议下一轮顺序 |
| 2026-10-14 | 待回 | Leader 验收结论（等待中） |
| 2026-10-14 13:05 | 收到 | **Leader 验收结论：通过**（8 节齐全；问题分级带现象/证据/影响/建议；第 6 节 10 项未核实显式列出）。**"后台鉴权无 P0"这个核心判定我独立复核并确认**：`AdminAuditController` 路由 10 / `requireAdminId` 10，`AdminManageController` 8/8，`AdminReviewController` 7/7，`/api/v1/admin` 仅这 3 个类映射 → 无裸奔接口；`role_flags` 无写接口（仅注册置 1）→ 无自我提权路径。这条结论价值很高，可以放心把#4 之外的 admin 侧风险降级。 |
| 2026-10-14 13:05 | 收到 | **裁定 ①** P1-1（批量通过不入队 `item_review`）**列为第 2 轮第一批**：它破坏的是"每条上架都被看过"的合规承诺，属审核闭环的完整性缺陷；修法按你的建议抽公共方法给单条/批量共用。**裁定 ②** P1-3（`expirePendingOrders` 读后写 vs 卖家 accept 丢失更新）：与你独立发现的"任务与接受并发"**与 order-member 的 P0 同源**（都指向"面交单状态被定时任务改坏"）——**第 2 轮与 order-member 的 P0 合并成一个任务、指定唯一写者**（默认 order-member 主写 `OrderService`，你主写 `ScheduledTasks`，两者必须同一批次联调，我会拆成两个有依赖关系的任务）。**裁定 ③** P1-6/问题 #4（`ai_audit_record` 落库）与 item-member 的 P1-3 是同一件事，**合并为一个任务**（一个写者，范围含 `ItemService` 审核调用点 + 新写入服务），避免两边各写一半。**裁定 ④** P1-2（关键词搜索分页后过滤）列入次批；P1-4（封禁不踢下线 + `ban_until` 只写不读）与 auth-member 的 P1-5 是同一根因，**合并为一个任务**（写者含 `AdminUserService` + user 域解封任务）。 |
| 2026-10-14 13:05 | 收到 | **裁定 ⑤** 5 个定时任务"全无分布式锁"**维持 P2**（当前单实例部署，无实际并发实例），但要求在第 2 轮修复任务里**一并加 `shedlock` 或条件更新幂等**，避免以后扩容时变成隐性 P1。P2 文档修正（错误码 24→36、#21 接口清单、#16/#20 编号）可零风险并行，第 2 轮单独一个只改文档的任务。第 6 节未跑的数字（"106ms 实测"等）不得写进任何结论，保持现状标注。 |
