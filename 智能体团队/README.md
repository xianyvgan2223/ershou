# 校园二手交易平台 · 智能体团队工作区

> 本文件夹是**智能体团队的记录中心**：每个成员一个文件，成员把自己那一轮的核实结论、发现的问题、下一步计划写进自己的文件。
> 规则：**一个功能 = 一个负责人 = 一个记录文件**；谁的文件谁写，别人只读。

- 建立时间：2026-10-10（第 1 轮）
- 团队规模：1 名 Leader + 7 名成员
- Leader：`lead`（负责分工、验收、汇总，不占成员记录文件）

---

## 一、名册与分工（每个成员负责一个功能）

| # | 成员 | 负责功能（唯一） | 记录文件 | 只读审计范围（读，不改） |
| --- | --- | --- | --- | --- |
| 1 | `auth-member` | **登录注册与会话**：手机号验证码、Sa-Token 会话、学校校区、个人资料、黑名单 | [`01-auth-登录注册与会话.md`](./01-auth-登录注册与会话.md) | `ershou-server/…/module/user`、`module/school`、`LoginView.vue`、`stores/user.ts`、`api/auth.ts`、`ProfileView.vue`、`ProfileEditDialog.vue`、`ProfileBlockView.vue`、`ershou_app/lib/ui/pages/login_page.dart`、`profile_page.dart` |
| 2 | `item-member` | **商品模块**：发布（AI 机审/限额/图片）、浏览搜索筛选分页（ES）、详情、下架、编辑、有效期与重新上架 | [`02-item-商品模块.md`](./02-item-商品模块.md) | `ershou-server/…/module/item`、`module/search`、`module/file`、`HomeView.vue`、`PublishView.vue`、`PublishListView.vue`、`ItemDetailView.vue`、`ItemCard.vue`、`ImageUploader.vue`、`ItemEditDialog.vue`、`ershou_app/lib/ui/pages/{home,publish,my_items,item_detail}_page.dart` |
| 3 | `map-member` | **校内地图与面交点**（核心）：高德真地图、Leaflet 降级、点位聚合/合并、选点针、附近窗口 | [`03-map-校内地图与面交点.md`](./03-map-校内地图与面交点.md) | `ershou-server/…/module/map`、`MapView.vue`、`AmapCampusMap.vue`、`CampusMap.vue`、`PointPickerDialog.vue`、`useTradePoints.ts`、`utils/{amap,coord,pointMerge,placeCache}.ts`、`ershou_app/lib/ui/pages/{map_page,point_picker_page,items_by_point_page}.dart`、`ui/widgets/map_*.dart` |
| 4 | `order-member` | **交易闭环**：收藏、购物车、面交预约（含合并面交）、面交单管理、信用分 | [`04-order-交易闭环.md`](./04-order-交易闭环.md) | `ershou-server/…/module/cart`、`module/order`、`module/item`（收藏部分）、`CartView.vue`、`FavoritesView.vue`、`OrdersView.vue`、`MeetupBookingDialog.vue`、`stores/{cart,meetup}.ts`、`ershou_app/lib/ui/pages/{cart,meetup_orders,favorites}_page.dart` |
| 5 | `chat-member` | **私聊 IM**：WebSocket 实时、断线重连、风控、聊天记录删除/批量删除、未读 | [`05-chat-私聊IM.md`](./05-chat-私聊IM.md) | `ershou-server/…/module/chat`、`ChatView.vue`、`api/chat.ts`、`stores/chat.ts`、`ershou_app/lib/core/network/chat_socket.dart`、`core/storage/chat_{cache,dao,database}.dart`、`ui/pages/chat_{list,}_page.dart` |
| 6 | `admin-member` | **运营后台与审核**：审核工作台（WebSocket 实时）、用户/商品/订单/日志、强制下架、AI 机审、定时任务 | [`06-admin-运营后台与审核.md`](./06-admin-运营后台与审核.md) | `ershou-server/…/module/admin`、`module/ai`、`module/system/task`、`views/admin/*`、`admin/` API、`stores/admin.ts`、`useAdminReviewSocket.ts`、`utils/adminDict.ts`、`styles/admin.scss` |
| 7 | `app-member` | **移动端基建**：局域网发现与连接、API 客户端、本地缓存/图片缓存、首页按需同步、生命周期 | [`07-app-移动端基建.md`](./07-app-移动端基建.md) | `ershou_app/lib/core/**`（`config`/`discovery`/`network`/`storage`/`utils`/`app_lifecycle.dart`/`providers.dart`）、`ui/pages/{connect,shell}_page.dart`、`ui/widgets/{net_image,common}.dart`、`ershou_app/pubspec.yaml`、`scripts/{lan-info,mobile-connect,mobile-install}.ps1` |

> 分工原则：**功能归属（不含端）**。同一个功能的后端 + Web + 移动端实现由**同一个成员**负责，避免"两端各说各话"。
> 跨模块基建（缓存/MapStruct/异常/TraceId 等 `common/**`）不单独派人，谁的功能用到谁核实；跨功能争议由 Leader 裁决。

---

## 二、记录文件规范（每个成员必须按此结构写）

成员文件固定 8 节，缺一节视为未完成：

1. **本轮状态**：`进行中 / 已完成 / 阻塞`，负责人，更新时间，覆盖的功能编号（对照 `功能清单.md`）
2. **功能边界**：这个功能做什么、不做什么（一句话写清，防止越界）
3. **代码地图**：入口 → 关键类/文件 → 数据表/缓存 key（全部带 `路径:行号`）
4. **已核实结论**：跑过/读到并确认成立的结论，**每条必须有证据**（`文件:行` 或 命令 + 输出片段）
5. **发现的问题**：按 `P0（阻断；含安全类：可被利用的越权、凭证泄漏、数据损坏）/ P1（影响体验或数据）/ P2（一致性/文档过期）` 分级；每条含 **现象 → 证据 → 影响 → 建议修法**；安全类必须在「影响」里写清**利用前提**（要不要同网、要不要用户交互）与**是否已真机/真环境验证**
6. **未核实 / 不确定**：明确写出"我没验证的东西"，**禁止把猜测写成结论**
7. **下一轮计划**：3~5 条可执行动作，标明是否需要改源码
8. **与 Leader 的往来**：收到的指令、提交的回报、Leader 的验收结论

**硬性纪律**

- **只写自己的记录文件**，不碰别人文件；**第 1 轮不改任何源码/配置/文档**（`src/**`、`*.vue`、`*.dart`、`*.md` 正文、`sql/**`、`scripts/**` 全部只读）。
- 结论必须可验证：能用 `文件:行号` 说清，就不许写"应该是"。
- 与 `功能清单.md` / `README.md` / `开发文档.md` 描述**不一致**的地方，一律按"文档过期"记 P2，并给出代码真实行为。
- 需要改代码的功能，写进"下一轮计划"，由 Leader 决定是否排期；不要自己动手。

---

## 三、协作规则

| 规则 | 内容 |
| --- | --- |
| 单一写者 | 每个记录文件只有 1 个写者（文件主人）。Leader 只在文件末尾追加"验收结论"。 |
| 源码冻结 | 第 1 轮为**只读核实轮**，全员不改源码。改代码必须由 Leader 在共享任务里单独排期并指定唯一写者。 |
| 证据优先 | 结论必须带 `文件:行号`；命令输出要贴关键行。没有证据的结论按未完成处理。 |
| 不启服务 | 第 1 轮不启动后端/前端/Flutter 构建（8080 只能有一个后端，避免互相踩端口）。只读代码与文档。 |
| 上报机制 | 完成后用 `send_message` 向 `lead` 回报（≤15 行）；Leader 汇总后决定下一轮。 |
| 阻塞处理 | 卡住超过一轮就写进"未核实/不确定"并立刻上报，不许静默等待。 |

---

## 四、第 1 轮状态看板

| 成员 | 共享任务 | 状态 | 记录文件 | 结论数 / P0·P1·P2 |
| --- | --- | --- | --- | --- |
| `auth-member` | T1 | ✅ 已完成（12:34） | [`01-auth-登录注册与会话.md`](./01-auth-登录注册与会话.md) | 16 / **1**·6·9 |
| `item-member` | T2 | ✅ 已完成（12:31） | [`02-item-商品模块.md`](./02-item-商品模块.md) | 18 / 0·4·9 |
| `map-member` | T3 | ✅ 已完成（12:35） | [`03-map-校内地图与面交点.md`](./03-map-校内地图与面交点.md) | 21 / 0·6·7 |
| `order-member` | T4 | ✅ 已完成（12:36） | [`04-order-交易闭环.md`](./04-order-交易闭环.md) | 30 / **1**·4·9 |
| `chat-member` | T5 | ✅ 已完成（12:38） | [`05-chat-私聊IM.md`](./05-chat-私聊IM.md) | 18 / 0·3·6 |
| `admin-member` | T6 | ✅ 已完成（12:37） | [`06-admin-运营后台与审核.md`](./06-admin-运营后台与审核.md) | 15 / 0·6·12 |
| `app-member` | T7 | ✅ 已完成（12:35） | [`07-app-移动端基建.md`](./07-app-移动端基建.md) | 16 / **1**·4·6 |
| `lead` | — | ✅ 已验收汇总（13:10） | [`第1轮汇总.md`](./第1轮汇总.md)（全局 P0 清单 + 第 2 轮排期） | 合计 134 / **3**·33·58 |

> 第 1 轮目标（已达成）：**把功能逐个核实到"代码级证据"**，产出一份可信的功能现状 + 问题清单，为第 2 轮修复排期提供依据。
> **第 1 轮结论**：3 条 P0（固定验证码后门 / 改期不续锁致二次售出 / 局域网可伪造服务器地址致凭证明文外发），33 条 P1，58 条 P2；7 名成员全部通过验收，**零源码改动**。
> 下一步：等用户批准后开工第 2 轮（批次 A = P0 与安全，见 [`第1轮汇总.md`](./第1轮汇总.md) 第六节）。

---

## 五、验收标准（Leader 用）

一个成员的第 1 轮才算完成，当且仅当：

1. 记录文件 8 节齐全，顶部状态为 `已完成`；
2. `已核实结论` ≥ 3 条，每条带 `文件:行号`；
3. `发现的问题` 每条带 现象/证据/影响/建议，并标了 P0/P1/P2；
4. `未核实` 一节明确写了没验证的内容（允许为空，但必须显式写"本轮无"）；
5. 已向 `lead` 回报，且回报里包含 Top 发现与证据；
6. 未修改任何源码/配置（Leader 用 `git status` 抽查）。

---

## 六、第 2 轮 · 平台代卖（官方严选）（2026-10-17）

> 本轮是**真开发轮**（第 1 轮是只读核实轮）。契约：[`第2轮-平台代卖-设计.md`](./第2轮-平台代卖-设计.md) ｜ 验收：[`第2轮-平台代卖-验收.md`](./第2轮-平台代卖-验收.md)

| 任务 | 成员 | 负责范围 | 状态 |
| --- | --- | --- | --- |
| T8 | `platform-dev`（本轮新增） | 后端 platform 模块 40 文件 + 迁移 SQL + Lua + 预热 + SaToken 白名单 | ✅ |
| T9 | `item-member` | 商品平台标注字段（Item/VO/ES） | ✅ |
| T10 | `order-member` | `OrderService.platformBuy` 平台交易（条件扣库存/完成/取消回补）+ `PlatformTradeMapper` | ✅ |
| T11 | `map-member`（代 web 职责，团队满员） | Web 平台严选专区页 + 首页标注 + 路由导航 | ✅ |
| T12 | `admin-member` | 后台平台代卖点管理页 + 菜单 | ✅ |
| T13 | `app-member`（代 QA 职责，团队满员） | 端到端验收脚本 + 验收报告 | ✅ |
| 裁决/收口 | `lead` | 契约、任务拆分、迁移落库（4 遍幂等）、SQL/并发缺陷裁决、文档收口、最终验收 | ✅ |

**验收结论**：后端编译 / Web 构建 exit 0；DB 64 PASS、API 132 PASS（含 5 并发秒杀防超卖）、Redis 36 PASS、Web 30 PASS，**0 FAIL**。

**本轮踩坑与修正（已同步进设计文档与开发日志）**
1. 平台单若走 `status=3` 锁库 → 多库存秒杀退化为串行、且被解锁任务误伤 → 改为**不锁库只条件扣库存**（v1.1）。
2. MySQL 单表 UPDATE 赋值从左到右求值 → 必须**先算 status 再扣 stock**（order-member 发现）。
3. 种子 `item.stock=1` 与 `seckill_stock=5` 冲突 → Lead 在迁移脚本追加幂等库存修正（QA 发现）。
4. 平台商品 `prefer_point_id` 若指向平台点会与 `trade_point` id 撞车 → 改为 `NULL`（Lead 审阅发现）。
5. QA 侧两处脚本假缺陷（PowerShell 逗号优先级、`mysql -e` 引号）→ 已定位为脚本 bug，产品无问题。

---

## 七、第 3 轮 · 平台运营台（独立控制台）（2026-10-18）

> 用户需求：给平台管理者一个**全新独立**的操作台（原嵌在运营后台里的代卖点页"显示得太小气"）。
> 契约：[`第3轮-平台运营台-设计.md`](./第3轮-平台运营台-设计.md) ｜ 验收：[`第3轮-平台运营台-验收.md`](./第3轮-平台运营台-验收.md)

| 任务 | 成员 | 负责范围 | 状态 |
| --- | --- | --- | --- |
| T14 | `platform-dev` | 后端 `PlatformConsoleController` 18 个管理接口 + 概览/订单/缓存统计服务 | ✅ |
| T15 | `admin-member` | 独立全屏 Shell + 路由迁移 + `api/platform-console.ts` + 概览页 + 平台商品页 + 后台入口 | ✅ |
| T16 | `map-member` | 代卖点 / 优惠券 / 秒杀场次 / 平台订单 / 缓存运维 5 页 + 商城入口 | ✅ |
| T17 | `app-member`（QA） | API 脚本 + Web 截图/视觉量化脚本 + 验收报告 | ✅ |
| 裁决/收口 | `lead` | 契约、缺陷裁决（3 项）、测试数据清理、文档收口、最终验收 | ✅ |

**验收结论**：后端编译 / Web 构建 exit 0；API **102 PASS / 0 FAIL**；Web **115 PASS / 0 FAIL / 1 SKIP**；视觉量化：1440×900 侧栏 248 / 顶栏 64 / 内容宽 1192（通栏）/ 表格溢出 0 / KPI 6，1920×1080 内容宽 1672。

**本轮缺陷闭环（QA 实测发现 → 修复 → 复测）**
1. overview / cache 两页顶栏被压缩到 43px：根因 `.pc__main` 是 flex 列容器、`.pc__top` 未设 `flex:none` → 修复后 64px。
2. Items 页根节点缺少运营台约定类 `.console-page`（QA 用它断言页面挂载）→ 补约定类。
3. cache 页顶栏同 ①，由 Shell 修复一并覆盖。

**环境已知问题**：`127.0.0.1:9211` 被非 ES 服务占用（返回 JWT 错误），后端 ES 写路径降级 MySQL LIKE；运营台不依赖 ES，验收不受影响（已记入报告 §5）。

---

## 八、第 4 轮 · 移动端平台严选 + 开发期取消商品限时（2026-10-18）

> 需求：①"移动端的平台严选页面呢"；②"开发期间取消商品限时显示限制，把之前过期的商品全部上架，要不然商品太少了"。
> 契约：[`第4轮-移动端严选与开发期限时-设计.md`](./第4轮-移动端严选与开发期限时-设计.md) ｜ 验收：[`第4轮-移动端严选与开发期限时-验收.md`](./第4轮-移动端严选与开发期限时-验收.md)

| 任务 | 成员 | 负责范围 | 状态 |
| --- | --- | --- | --- |
| T18 | `app-member` | Flutter 平台严选页（3 新文件）+ 首页入口 + 商品卡/详情「官方代卖」标注 + Item 模型 sellerType | ✅ |
| T19 | `item-member` | `ershou.item.validity-enabled` 开关 + 发布/重上架不写 expireAt + 过期任务跳过 + relist SQL | ✅ |
| T20 | `chat-member`（本轮 QA） | 验收脚本 + 端到端验收 + 真机截图 + 报告 | ✅ |
| 落库/收口 | `lead` | 执行 relist SQL（幂等两跑）、`.env` 开关、Web null 文案修复、文档收口 | ✅ |

**验收结论**：**108 PASS / 0 FAIL / 1 WARN**；DB 在售 3→16 件、过期 0；限时任务跳过日志 4 次/40s、下架 0 次；`flutter analyze` 0 error/0 warning、`flutter test` 51/51、`build apk` 成功；**真机 KOZ-AL00 截图**（首页「严选」入口+官方代卖角标、平台严选页四大区块）。

**本轮踩坑**
1. MyBatis-Plus `NOT_NULL` 策略下实体 `setExpireAt(null)` 不会进 SET → 清空必须用 wrapper `.set(Item::getExpireAt, null)`。
2. Web `PublishListView` 只判状态不判 `expireAt` → 无到期时间时文案误导；已补 `&& item.expireAt`。
3. 8080 是**用户 IDEA 实例**，不能 kill；QA 用独立 18080 + `adb reverse 手机8080→宿主18080` 完成真机验收。
4. 既有 `ScheduledTasks.flushViewCounts` NPE（双实例共享 Redis 触发）非本轮引入，记 P2。

---

## 九、第 5 轮 · Spring Cloud 微服务重构（演示版）（2026-10-19）

> 需求："用智能体团队把项目用 Spring Cloud 重构，主要做功能和技术演示；Redis 用现有但要数据隔离；数据库要拆分；用 RabbitMQ 优化；选 Nacos、Sentinel、Seata、Spring AMQP、OpenFeign、Spring Cloud Gateway；用 Docker。"
> 契约：[`第5轮-SpringCloud重构-设计.md`](./第5轮-SpringCloud重构-设计.md) ｜ 验收：[`第5轮-SpringCloud重构-验收.md`](./第5轮-SpringCloud重构-验收.md)

| 任务 | 成员 | 交付 | 状态 |
| --- | --- | --- | --- |
| T24 | `platform-dev` | parent + common（12 类）+ Gateway（8 类：路由/Sa-Token/X-User-Id/Sentinel 限流/CORS/TraceId）+ Nacos 配置 4 个 + Seata/分页版本对齐 | ✅ |
| T25 | `admin-member` | user-service（认证/用户/学校；会话 db0 与单体互通，16/16 断言） | ✅ |
| T26 | `item-member` | item-service（商品读/缓存/内部库存锁/MQ 消费/分页修复） | ✅ |
| T27 | `order-member` | order-service（Feign/Seata AT 回滚/MQ 生产/Nacos 动态配置） | ✅ |
| T28 | `chat-member` | Docker 编排验证 + 建库/迁移 SQL（223 行）+ cloud-up/down + Dockerfile 实构建 + 中间件证据 | ✅ |
| T29 | `map-member` | 前端接网关（vite env 化 + `.env.cloud`）+ `cloud-demo.ps1`（11/11 PASS）+ demo-script 文档 | ✅ |
| T30 | `app-member` | cloud README + architecture（4 mermaid）+ migration-roadmap | ✅ |
| 基础设施/整合 | `lead` | 接手 compose/Seata/Sentinel 镜像与启动、Nacos 发布、Seata 版本裁决、E2E 复核、数据重置、文档收口 | ✅ |

**验收结论**：5 模块 BUILD SUCCESS；Docker 四中间件健康；Nacos 4 服务 + 4 配置；MySQL 三库独立账号（迁移 223 行）；**网关端到端 11/11 步 PASS、29/29 断言**；Seata AT 回滚、RabbitMQ 异步（sale_count+1）、Sentinel 429、Nacos 配置热更新、Redis db0/1/2/3 隔离全部实锤。

**本轮踩坑（已同步开发日志）**
1. SCA 2023.0.3.2 默认带 Apache Seata 2.1.0，与 Docker 镜像 io.seata 1.8.0 不兼容 → 父工程固定 io.seata 1.8.0 + 排除 org.apache.seata。
2. Docker Desktop 容器 IP 宿主机不可达 → Seata 客户端 `registry.type=file + grouplist=127.0.0.1:8091`；可选 SEATA_IP 改进已写入 docker README（未启用）。
3. MyBatis-Plus 3.5.9 分页拆包（`mybatis-plus-jsqlparser`）；Sa-Token 双 Dao（`@Primary` db0）；网关 429 统一 JSON（最高优先级 WebExceptionHandler）；PS 5.1 中文脚本需 UTF-8 BOM。

---

## 十、第 6 轮 · 优化空间分析 + 功能页面漏洞审计（2026-10-21，只读分析轮）

> 用户要求："还有什么可以优化的空间吗，先不要改代码，先提出，还有调用智能体团队分析各个功能页面可能的漏洞。"

**分工**（T31–T38，8 名成员并行只读审计；唯一写入=各自报告文件）：
| 任务 | 成员 | 审计范围 | 报告 |
| --- | --- | --- | --- |
| T31 | auth-member | 认证/登录注册/我的/公开主页 | `第6轮-安全审计/T31-auth-认证与用户页面.md` |
| T32 | item-member | 首页/搜索/发布/详情/我的发布/收藏 | `T32-item-商品页面.md` |
| T33 | map-member | 地图/面交点/收藏/面交 | `T33-map-地图与面交页面.md` |
| T34 | order-member | 购物车/面交单/信用分/我的交易 | `T34-order-交易闭环页面.md` |
| T35 | chat-member | 私聊 IM/WebSocket | `T35-chat-私聊IM页面.md` |
| T36 | admin-member | 管理后台/平台运营台（49 个管理接口） | `T36-admin-管理后台与运营台.md` |
| T37 | app-member | Flutter 17 个页面/本地存储/Android 配置 | `T37-app-移动端页面.md` |
| T38 | platform-dev | 平台严选/代卖/券/秒杀/运营台 + 云配置 | `T38-platform-平台严选与秒杀.md` |
| 汇总 | lead | 去重/复核/修复排期 + 优化建议 | `第6轮-安全审计/汇总报告.md`、`第6轮-优化建议.md` |

**结果**：154 条发现（P0×3 / P1×33 / P2×61 / P3×57）。全局最危险：①固定验证码 123456 → 任意账号（含管理员）接管；②Nacos 未授权 + 明文凭据入库 + 默认密钥 + 中间件对外 → 批量冒用/窃听；③云服务直连伪造 X-User-Id + `/inner/**` 无鉴权 → 扣光库存。另有功能性问题：网关把面交单全部路由到新 order-service 但只实现了创建/我的列表 → 经 8088 的面交详情/操作 404（演示前需修）。
**纪律**：未改任何源码/配置/DB，未启服务、未发攻击请求、未做写操作/DoS；全部静态确认，动态验证方案见各报告第 5/6 节。
**排期建议**：演示前先修 5 项（关 mock、凭据/网络收敛、云边界、网关 404、移动端缓解），详见 `第6轮-安全审计/汇总报告.md` 第四节。