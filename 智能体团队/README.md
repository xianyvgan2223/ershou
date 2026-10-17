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
