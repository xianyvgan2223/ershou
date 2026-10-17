# 私聊 IM · chat-member 记录

| 项 | 值 |
| --- | --- |
| 成员 | `chat-member` |
| 负责功能 | 私聊 IM：WebSocket 实时收发、断线可见与重连自愈、新号限额风控、聊天记录删除/批量删除（每用户独立软删）、未读角标 |
| 记录文件 | `智能体团队/05-chat-私聊IM.md` |
| 本轮状态 | `已完成` |
| 更新时间 | `2026-10-14` |
| 覆盖功能编号 | 功能清单 #15（私聊 IM）、#21（聊天记录删除 + 批量删除）、问题 #11（无离线 Push / 无"谁可以私聊我"） |

## 1. 本轮状态

- 状态：**已完成**（第 1 轮只读核实轮）。负责人 `chat-member`，共享任务 `task-5`，更新时间 2026-10-14。
- 覆盖功能：`功能清单.md:27`（#15）、`功能清单.md:38`（#21）、`功能清单.md:427`（问题 #11）；附带核实 `功能清单.md:245`（拉黑双向）、`功能清单.md:237`（未读 DB 权威 + 僵尸字段）。
- 本轮纪律执行情况：**未修改任何源码/配置/文档/脚本**；**未启动后端/前端/Flutter**；**未运行** `scripts/chat-smoke.mjs`、`scripts/probe-chat-*.mjs`、`scripts/verify-chat-*.mjs`、`scripts/repro-chat-batch-delete.mjs`（这些需要运行中的 8080/5173 服务），仅把它们当只读材料参考（如 `scripts/verify-chat-delete.mjs:4-11` 的断言清单）。
- 本轮唯一写入文件：`智能体团队/05-chat-私聊IM.md`。
- 结论口径：本文所有 `路径:行号` 均来自本轮真实 read/grep 的内容；凡是没读到的（运行时行为、SQL 是否已 apply、真实投递时延）一律放第 6 节，不写进结论。
- 一句话结论：**没有发现 P0**（WS 握手有鉴权、无越权发消息通道、软删不泄漏对方消息）；发现 **3 条 P1（其中 2 条是 Web 端未读/断线消息的可见性缺陷）** 与 **6 条 P2**。

## 2. 功能边界

- 做什么（本文件负责的范围）：
  - 私聊**后端全链路**：会话创建/列表/详情、历史消息游标、发消息（限额 → 机审 → AES 加密落库 → 会话摘要与未读 → 推送）、撤回、已读、未读汇总、快捷话术。
  - 私聊**实时通道**：`/ws/chat` 握手鉴权、心跳/看门狗、在线态 TTL、本机直推 + Redis Pub/Sub 跨实例广播、断线后的消息可见性（落库优先）。
  - **聊天记录删除 / 批量删除**：`deleted_a`/`deleted_b` 每用户软删、对方不受影响、对方再发消息自动恢复、批量删除的失败明细与事务边界、迁移脚本与实体一致性。
  - **未读角标**：DB 权威计数、Redis 快照自愈与僵尸字段清理、Web 侧栏「消息」红点与进页面清零、移动端底部导航红点。
  - **移动端聊天实现**：本地 SQLite 镜像（`chat_session`/`chat_message`/`chat_sync`）、`beforeId`/`afterId` 游标、断线重连自愈与去重、渲染入口的字段兜底。
- 不做什么（越界即算别人的功能，本文件只在"影响私聊"时提及）：
  - 登录注册与 Sa-Token 会话本体、手机号验证码、学校校区（`auth-member`）；本文件只核实"私聊怎么用登录态"。
  - 黑名单的增删接口与「我的黑名单」页面本身（`auth-member` / 功能清单 #24）；本文件只核实"拉黑在私聊里的双向拦截"。
  - 面交单状态机与交易卡片动作（`order-member`）；本文件只核实"聊天窗里的卡片调用了哪些接口"。
  - 商品详情/搜索（`item-member`）；本文件只核实"私聊会话的商品归属校验"。
  - 图片上传与 MinIO（`item-member`/基建）；本文件只核实"图片消息存 URL、正文不加密"。
  - 移动端基建的局域网发现、API 客户端、图片缓存、首页按需同步（`app-member`）；本文件只核实聊天用到的 `chat_socket`/缓存/生命周期钩子。

## 3. 代码地图

**入口（后端）**

| 层 | 位置 | 说明 |
| --- | --- | --- |
| REST 入口 | `ershou-server/src/main/java/com/ershou/campus/module/chat/controller/ChatController.java:28` | `@RequestMapping("/api/v1/chat")`，12 个接口 |
| REST：会话列表 | `ChatController.java:36-39` | `GET /sessions?includeDeleted=` |
| REST：建会话 | `ChatController.java:43-45` | `POST /sessions`（按 itemId，复用已有） |
| REST：删除/批量删除 | `ChatController.java:54-58`、`ChatController.java:61-64` | `DELETE /sessions/{id}`、`POST /sessions/batch-delete` |
| REST：历史消息 | `ChatController.java:67-73` | `GET /sessions/{id}/messages?beforeId=&afterId=&size=` |
| REST：发消息兜底 | `ChatController.java:76-80` | `POST /messages`（WS 不通时用） |
| REST：已读/未读/状态 | `ChatController.java:89-93`、`96-99`、`108-111` | `/read`、`/unread`、`/status` |
| 鉴权（REST） | `ershou-server/src/main/java/com/ershou/campus/common/config/SaTokenConfig.java:15-26` | `SaRouter.match("/api/**")` 白名单里**没有** `/api/v1/chat/**` → 全部需登录 |
| 业务核心 | `module/chat/service/ChatService.java` | 会话/消息/风控/未读 685 行 |
| 风控留痕 | `module/chat/service/ChatRiskRecorder.java:30-51` | `REQUIRES_NEW` 独立事务写 `blocked=1` |
| 推送 | `module/chat/service/ChatPushService.java` | 本机 session 直推 + Pub/Sub |

**WebSocket 通道**

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 注册 | `module/chat/config/ChatWebSocketConfig.java:26-33` | `/ws/chat` + 握手拦截器；`setAllowedOriginPatterns("*")`；`ershou.chat.enabled=false` 时**整个不注册** |
| 握手鉴权 | `module/chat/ws/ChatHandshakeInterceptor.java:26-42` | 无 token → 401；`StpUtil.getLoginIdByToken` 失败 → 401；成功写 `ATTR_USER_ID` |
| token 来源 | `ChatHandshakeInterceptor.java:50-65` | 先 `?token=`，再 `Authorization: Bearer` |
| 帧处理 | `module/chat/ws/ChatWebSocketHandler.java:62-96` | `ping`→`pong`+`touch`（`:75-79`）；`read`→`markRead`（`:80-83`）；`send`→`chatService.send`+`ack`（`:98-106`） |
| 上下线 | `ChatWebSocketHandler.java:47-60`、`108-114` | 上线 `register`+`ready`；下线 `unregister`；无 userId 直接 `closeQuietly`（`:135-141`） |
| 在线态 | `ChatPushService.java:55-61`、`RedisKeys.java:122-124` | `ershou:chat:online:{userId}`，TTL 90s，**断线不主动删**（`:51-54` 注释） |
| 跨实例 | `ChatPushService.java:84-118`、`ChatRedisConfig.java:21-30`、`RedisKeys.java:127-129` | `ershou:chat:channel:msg`，`instanceId` 跳过回声（`ChatPushService.java:121-138`） |
| 参数默认值 | `module/chat/config/ChatProperties.java:15,18,21-22,31,34,37,40,43,46,49-50` | 全部默认值；`application*.yml` 里**没有任何** `ershou.chat.*` 覆盖 |

**数据表 / 缓存 key**

| 对象 | 位置 | 说明 |
| --- | --- | --- |
| `chat_session` | `sql/schema.sql:247-271` | `uk_session_pair_item(user_a,user_b,item_id)`（`:266`）；软删列 `:259-262`；索引 `:267-270` |
| `chat_message` | `sql/schema.sql:273-288` | `content` 标注 AES 加密（`:280`）；`blocked`（`:282`）、`recalled`（`:283`） |
| `sys_user.chat_limit` | `sql/schema.sql:91` | `INT NOT NULL DEFAULT 20` |
| 迁移脚本 | `sql/migration-chat-session-delete.sql:28-37`（加 4 列）、`:40-51`（加 2 个索引） | 幂等（information_schema 判断） |
| Redis 未读 | `RedisKeys.java:117-119` | Hash `ershou:chat:unread:{userId}`，field=sessionId |
| Redis 限额 | `RedisKeys.java:28-30` | `ershou:limit:chat:{userId}:{yyyyMMdd}` |

**软删 / 未读关键实现**

| 语义 | 位置 |
| --- | --- |
| 每用户软删字段与判定 | `module/chat/entity/ChatSession.java:51-54`（`deletedA/deletedB/deletedAAt/deletedBAt`）、`:70-72`（`deletedFor`） |
| 写软删（未读一起归零） | `module/chat/mapper/ChatSessionMapper.java:37-43` |
| 撤销软删（**双方**一起清） | `ChatSessionMapper.java:52-55` |
| 删除接口 | `ChatService.java:148-165`（幂等 `:152-156`；清 Redis 未读 `:159-163`） |
| 批量删除 | `ChatService.java:177-200` |
| 我删过的会话从列表剔除 | `ChatService.java:214-226`（过滤在 Java 流里，`:222`） |
| 对方再发消息自动恢复 | `ChatService.java:425-428`（在 `insert` `:412` 之后、推送 `:436` 之前，同一事务） |
| 从商品页重新开启也撤销删除 | `ChatService.java:120-127` |
| 未读 +1（DB 权威） | `ChatSessionMapper.java:16-20`、`ChatService.java:557-570` |
| 已读（DB 归零 + 清 Redis + 通知对方） | `ChatService.java:253-270` |
| 未读汇总 + 僵尸字段清理 | `ChatService.java:273-337`，清理在 `:318-325` |

**Web 端**

| 层 | 位置 | 说明 |
| --- | --- | --- |
| WebSocket 生命周期 | `ershou-web/src/stores/chat.ts:52-160` | URL 带 `?token=`（`:52-55`）、重连退避 5 次（`:101-106`）、心跳 30s + 看门狗（`:108-133`） |
| 帧分发 | `stores/chat.ts:162-224` | `ready`→`loadUnread`（`:163-169`）；`message`（`:177-193`）；`ack`（`:195-200`）；`recall`（`:201-213`）；`error`（`:219-223`） |
| 消息归一化 | `stores/chat.ts:236-255`、`:269-280`、`:288-319` | 历史/`message`/`ack`/`recall` 四条路（`:178,196,203,301,362`） |
| 发消息 | `stores/chat.ts:446-476` | WS 优先，`send()` 失败回落 REST（`:466-475`） |
| 已读与红点 | `stores/chat.ts:432-443`、`layouts/DefaultLayout.vue:104-109`、`:171-172` | 进会话 `markRead` 后 `unreadTotal` 递减 |
| 页面 | `views/ChatView.vue:205-209`（开会话）、`:217-234`（单删）、`:268-301`（批量删）、`:314-326`（发送）、`:397-408`（`canRecall` 守卫）、`:427-456`（watch/onMounted）、`:644-647`（断线条）、`:721-726`（加载更早） |
| API | `src/api/chat.ts:82-87`（只用 `beforeId`）、`:112-114`、`:122-128` | 发消息 `:90-92`、撤回 `:95-97`、未读 `:103-105` |

**移动端**

| 层 | 位置 | 说明 |
| --- | --- | --- |
| WS 客户端 | `ershou_app/lib/core/network/chat_socket.dart:19-20`（30s 心跳 / 75s 静默判死）、`:49-79`（连接）、`:82-88`（回前台重连）、`:90-99`（只发 ping，消息走 REST）、`:101-112`（看门狗）、`:115-129`（退避 1/2/4/8s） |
| 帧模型 | `core/network/chat_events.dart:7-23`（帧类型）、`:33-47`（信封解析）、`:50-58`（`id==0` 视为非法） |
| 本地库 | `core/storage/chat_database.dart:29-90`（3 张表；`:79` 服务端 id 唯一索引） |
| DAO | `core/storage/chat_dao.dart:7-8`（`s:<id>` / `c:<clientMsgId>` 主键）、`:125-156`（写消息 + 水位 `chat_sync`）、`:159-168`（读最近 N 条）、`:199-207`（30s 未回包判失败）、`:209-215`（`maxServerId`）、`:246-252`（删会话三表一起删） |
| 合并/去重 | `core/storage/chat_cache.dart:22-47`（`mergeMessages`）、`:50-69`（`mergeSessions`） |
| 聊天页 | `ui/pages/chat_page.dart:69-79`（缓存先渲染→网络）、`:105-116`（5s 轮询兜底，`silence<20s` 才跳过）、`:119-159`（帧分发）、`:122-124`+`:236-272`（`ready`→`_fillGap` 增量补拉）、`:161-174`（`_upsertMessage` 去重）、`:185-203`/`:205-228`/`:275-309`（缓存/全量/翻旧）、`:339-395`（乐观消息 + REST 发送）、`:761-776`（离线提示条） |
| 会话列表 | `ui/pages/chat_list_page.dart:57-87`（缓存→服务端→未读）、`:109`（版本号 600ms 防抖刷新）、`:247-336`（批量删）、`:342-390`（单删）、`:535-546`（未读角标） |
| 全局 | `core/providers.dart:120-129`（全局唯一 socket）、`:202-266`（事件路由：未读/缓存/版本号）、`:231-238`（正在看的会话直接标已读）、`:306-323`（未读数）、`:36-43`（缓存 scope）、`:444-455`（登出清 scope） |
| 生命周期 | `core/app_lifecycle.dart:34-44`（回前台 `ensureConnected` + 校准未读） |
| 模型/仓库 | `data/models2.dart:86-158`（强类型 `ChatMessage` + `fromJson`）、`data/api_repository2.dart:36-55`（`beforeId`/`afterId`）、`:57-73`、`:89-91`、`:97-109`、`:112-118` |

## 4. 已核实结论

| # | 结论 | 证据 |
| --- | --- | --- |
| C1 | **WS 握手有鉴权**：无 token / token 无效一律 401 拒绝握手；token 从 `?token=` 或 `Authorization: Bearer` 取；`/ws/**` 确实不在 Sa-Token 的 `/api/**` 拦截范围内，所以这段自校验是唯一防线且**存在** | `ChatHandshakeInterceptor.java:29-41`、`:50-65`；`SaTokenConfig.java:15-26`；`ChatWebSocketConfig.java:30-32` |
| C2 | **不存在"给任意用户发消息"的越权通道**：`send` 的接收者由会话本身推导（`session.peerOf(senderId)`），且所有消息接口先过 `requireMember`（非参与者一律 70002，且不区分"不存在/不是参与者"以防探测会话 id）；建会话只能按 `itemId`，对方固定为商品 `sellerId` | `ChatService.java:374-376`、`:505-515`、`:81-92` |
| C3 | **落库优先，绝不"推了没存"**：`send` 依次限额→机审→`insert`（`:412`）→会话摘要（`:414-422`）→恢复接收方软删（`:425-428`）→未读 +1（`:429`）→推送（`:436`）；任一步抛异常都不回 ACK（WS 侧只有 `handleSend` 成功才发 `ack`，`ChatWebSocketHandler.java:104-105`）。断线期间的消息只在 DB 里，靠 REST 补 | `ChatService.java:373-439`、`ChatWebSocketHandler.java:88-95` |
| C4 | **心跳与看门狗两端都有**：服务端 `ping`→`pong` 并刷新在线 TTL 90s；Web 端 30s 心跳 + 超 2 周期无 pong 判假死主动断开重连；移动端 30s 心跳 + 75s 无任何帧判半死重连 | `ChatWebSocketHandler.java:75-79`；`ChatPushService.java:55-61`；`ChatProperties.java:37`；`stores/chat.ts:108-133`；`chat_socket.dart:19-20`、`:101-112` |
| C5 | **在线态是"连接/心跳续期 + TTL 自然过期"**，断线不主动删 key（避免多标签页互踩、刷新页面误判离线）；`peerOnline` 由 Redis key 是否存在决定 | `ChatPushService.java:51-61`、`:63-72`；`ChatService.java:612`、`:674-680` |
| C6 | **拉黑在私聊里双向生效**：建会话与发消息都走 `blockedEither`（任一方拉黑即拦，70006）；会话 VO 下发 `blockedByMe/blockedByPeer/canSend`；两端 UI 都据此禁用输入 | `ChatService.java:90-92`、`:377-380`、`:613-618`；`UserBlockService.java:78-85`；`ChatView.vue:797-798`（红条）、`:818-826`（输入框/按钮 `disabled=!canSend`）；`chat_page.dart:544-548`、`:800-810` |
| C7 | **新号限额默认关闭**：`ershou.chat.*` 在 `application*.yml` 里完全没有配置项，生效值全部来自 `ChatProperties` 默认值 —— `dailyLimitEnabled=false`；限额路径（`RedisKeys.chatLimit` = `ershou:limit:chat:{userId}:{yyyyMMdd}`，计数到次日 23:59:59，`sys_user.chat_limit` 缺省 20，Redis 异常时**放行**）代码保留、开关打开即生效 | `ChatProperties.java:31`、`:34`；grep `ershou\.chat`（资源目录 0 命中）；`ChatService.java:525-554`；`RedisKeys.java:28-30`；`sql/schema.sql:91` |
| C8 | **未读以 DB 为权威**：`/unread` 无论缓存是否命中，都先查"我的未删除会话"，逐会话用 DB 的 `unread_a/unread_b` 作为返回值；Redis 只用来标记"哪些会话存在"，并对被删会话留下的 field 做清理（含 `log.info` 可观测） | `ChatService.java:273-337`（DB 回填 `:299`/`:316`/`:328`，清理 `:302-325`）、`ChatSessionMapper.java:16-26` |
| C9 | **软删是每用户独立的**：`deleted_a`/`deleted_b` 分侧标记（`markDeleted` 用 `IF(#{sinceA},1,...)` 同时把我这侧未读归零），列表（`:222`）与未读汇总（`:281`）对"我删过的"做剔除，而**消息行一行都没删**（`deleteSession` 全流程无 delete 语句）；对方列表/未读不受影响 | `ChatSessionMapper.java:37-43`；`ChatService.java:148-165`、`:214-226`、`:279-281` |
| C10 | **"对方再发消息自动恢复"落在发送路径**：以接收方视角判断 `session.deletedFor(receiverId)` → `restoreDeleted`（并记 `log.info`），位置在消息落库之后、推送之前，同一事务内，不会出现"存了但看不到" | `ChatService.java:425-428`；`ChatSessionMapper.java:52-55`；`ChatSession.java:70-72` |
| C11 | **迁移脚本与实体/建表一致**：迁移加 4 列（`deleted_a/deleted_b/deleted_a_at/deleted_b_at`）与 2 个索引，`schema.sql` 的列、索引、`uk_session_pair_item` 完全对得上；实体字段 `deletedAAt/deletedBAt` 按 MyBatis-Plus 规则映射为 `deleted_a_at/deleted_b_at`（`ChatSession.java:46-49` 记录了历史上写错字段名导致 90001 的教训） | `sql/migration-chat-session-delete.sql:28-37`、`:40-51`；`sql/schema.sql:259-262`、`:266`、`:269-270`；`ChatSession.java:51-54` |
| C12 | **风控拦截的可见性是"仅发送方"**：高危消息以 `blocked=1` 留痕并推 `RISK_BLOCKED`（50002）；历史查询条件为 `blocked=0 OR sender_id=我`，接收方永远读不到；留痕走 `REQUIRES_NEW`，不会被外层回滚带走 | `ChatService.java:393-401`、`:354-357`；`ChatRiskRecorder.java:30-42`；`功能清单.md:246` |
| C13 | **正文加密确实落库（AES/GCM + 随机 IV，`v1:` 前缀）**，撤回后连发送方都不再下发原文 | `ContentCipher.java:29`、`:47-64`；`ChatService.java:409`、`:649-659` |
| C14 | **Web 端未读红点语义正确**：布局挂载时连接 + 拉未读（切页不断线），侧栏「消息」红点显示 `unreadTotal`；进入会话 `markRead` 成功后按差值递减，所以"未读保留、进页面后徽章消失"成立 | `layouts/DefaultLayout.vue:103-109`、`:171-172`；`stores/chat.ts:432-443`；`views/ChatView.vue:437-446` |
| C15 | **移动端有真正的增量补拉与去重**：本地 `chat_sync.last_msg_id` 水位（`maxServerId`）→ `ready` 帧触发 `_fillGap()` 用 `afterId` 拉增量；增量满一页或报错回退全量；`mergeMessages` 按服务端 id / `clientMsgId` 去重，且保留本地"发送中/失败"行不被服务端窗口冲掉；轮询兜底以"在线且 20s 内有帧"为跳过条件 | `chat_page.dart:105-116`、`:122-124`、`:236-272`；`chat_cache.dart:22-47`；`chat_dao.dart:142-155`、`:209-215`；`api_repository2.dart:36-55` |
| C16 | **移动端渲染不会因缺字段而崩**（对应 Web 的"按 Enter 卡死"那类坑）：消息是强类型 `ChatMessage`，`fromJson` 对所有字段给默认值（`msgType` 为 0 时归 1、`createdAt` 缺失为 `''`），所有入口（REST 历史/发送/撤回、WS 帧）都经 `ChatMessage.fromJson`/`ChatFrame.asMessage`，不存在"原样塞进列表"的旁路 | `models2.dart:86-158`（`fromJson` `:145-157`）；`chat_events.dart:50-58`；`api_repository2.dart:51,70,79`；`chat_page.dart:344-352`（乐观消息走构造函数，字段齐全） |
| C17 | **问题 #11 仍成立**：全仓无任何 APNs/FCM/厂商推送的代码或依赖（`pubspec.yaml` 依赖里没有推送 SDK），也没有"谁可以私聊我"的开关/接口/字段；PRD 里 `FR-UP-06 隐私设置` 标 P0，`FR-UP-04` 的商品侧过滤同样未做（后者归 `item-member`） | grep `firebase\|fcm\|apns\|jpush\|getui\|push_token` → 仅文档命中；`pubspec.yaml:30-56`；grep `谁可以私聊\|allowChat\|chat_privacy` → 仅文档命中；`校园二手交易平台需求文档.md:391`；`功能清单.md:250`、`:427` |
| C18 | **`git status` 抽查口径（含"非本轮改动"的说明）**：本轮只 read/grep，未执行任何写操作，唯一写入为本记录文件。抽查时若看到 `README.md`、`CacheProperties.java`、`JsonCache.java`、`ItemService.java`、`开发文档.md`、`开发日志.md` 的改动与未跟踪的 `scripts/audit-cache.mjs`，**这些不是本轮产生的**：其文件修改时间均为 **2026-10-13 08:59~09:14**（早于本轮 2026-10-14 12:3x），属上一轮遗留；`智能体团队/` 整个目录为未跟踪，故 `git status` 无法用路径隔离单个成员文件 | 本轮执行的 `git -C E:\ershou status --porcelain` 与 `git diff --stat`（6 files changed, 233 insertions(+), 37 deletions(-)）；`Get-ChildItem` 显示的 6 个文件 LastWriteTime 均 ≤ 2026-10-13 09:14；`智能体团队/05-chat-私聊IM.md` 的 LastWriteTime 为 2026-10-14 12:36 |

## 5. 发现的问题

| # | 级别 | 现象 | 证据 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- |
| P1-1 | **P1** | **Web 端断线期间的消息不会补齐，且一进会话就把未读清零**：重连成功只拉未读汇总，不补消息；`openSession` 在"本地已有该会话消息"时**不再回源**，随后 `markRead` 把红点清掉 → 用户停在聊天窗时会**静默看不到**对方在断线期间发的消息，只能整页刷新 | `stores/chat.ts:163-169`（`ready` → 仅 `loadUnread`）、`:236-255`（无 `afterId` 概念）；grep `afterId` 于 `ershou-web/src` **0 命中**（只有 `beforeId` 翻旧，`:427`）；`:357-364`（`if (!messages.value[sessionId])` 才拉历史）、`:432-443`（无条件清零）；对照移动端已实现 `_fillGap`（`chat_page.dart:236-272`） | 断网/休眠/后端重启后，用户以为"没人找我"；消息实际已落库（C3），但 Web 侧没有任何入口能把它拉回来，直到刷新整页；`ChatView.vue:646` 提示"可以下拉刷新补齐"**页面上根本没有这个功能**（只有"加载更早的消息" `:721-726`） | `ready` 分支改为：先 `loadSessions()`，再对 `currentSessionId` 用 `afterId = 最后一条服务端消息 id` 增量补拉（后端能力已就绪：`ChatController.java:70`、`ChatService.java:358-360`），补拉失败或满页时回源全量；同时把 `:646` 的文案改成与真实能力一致 |
| P1-2 | **P1** | **离开 `/chat` 后 `currentSessionId` 未清空**：只要曾经打开过某会话，之后人在别的页面（窗口仍有焦点）收到该会话的新消息时，`document.hasFocus()` 为真 → 直接调 `markRead`，**导航「消息」红点不涨、消息也不显示**，用户完全无感 | `stores/chat.ts:184-189`（`currentSessionId === msg.sessionId && document.hasFocus()` → `markRead`）；`currentSessionId` 只在 `:358`（开会话）与 `:413`（忘记会话）被写；grep `onUnmounted\|onBeforeUnmount` 于 `ChatView.vue` **0 命中**（`:427-456` 只有 watch/onMounted）；红点口径 `DefaultLayout.vue:171-172`。对照移动端有 `activeSession.leave()`（`chat_page.dart:85`） | 未读漏报 = 用户漏掉真实交易消息（对二手平台是核心链路：约面交/接受/拒绝都靠私聊通知，见 `功能清单.md:170`、`:191`） | 二选一：(a) `ChatView` 加 `onBeforeUnmount` 调 `chat.setCurrentSession(null)`（或 store 暴露 `leaveSession()`）；(b) 判断条件改为"路由确实在 /chat 且会话列表选中该会话"，例如用 `route.path === '/chat'` 参与判断 |
| P1-3 | **P1** | **聊天正文加密形同虚设**：`ershou.crypto.chat-key` 没有配置，代码里用**源码内置默认值** `__REDACTED_CHAT_KEY__` 派生密钥；另外只有 `msgType==1` 才加密，而 WS 路径的 `msgType` 直接取客户端值且无白名单 → 客户端传 `msgType=2` 即可让任意文本**明文落库** | `ContentCipher.java:36`（`@Value("${ershou.crypto.chat-key:__REDACTED_CHAT_KEY__}")`）、`:39-41`（SHA-256 前 16 字节当密钥）；`application.yml:78-80`（只配了 `phone-key`，无 `chat-key`）；`ChatService.java:409`（`type == MSG_TEXT ? cipher.encrypt : content`）；`ChatWebSocketHandler.java:101`（`msgType` 直接取帧）；`sql/schema.sql:280`（schema 声明 content 为 AES 加密） | 任何拿到仓库/镜像的人可用默认密钥解密**任意部署实例**的聊天记录；DBA/备份泄漏≈明文。发消息方还能绕过加密写入明文（并且 `toMessageVO` 只在 `msgType==1` 时解密 `:656-658`，该行会被前端当图片渲染，形成"看得见的异常"） | ① `ershou.crypto.chat-key` 改为环境变量注入（与 `phone-key` 同口径），缺省时应**启动失败**而不是退回默认值；② `msgType` 做 1..6 白名单校验（REST 的 `SendMessageDTO` 与 WS 的 `handleSend` 都加）；③ 加密判据改为"内容本身是文本"（例如按 msgType 白名单里的文本类决定），不要用客户端可控字段单独决定 |
| P2-1 | **P2** | **批量删除的"逐条独立处理"与事务边界自相矛盾**：`batchDelete` 自己标了 `@Transactional`，内部循环自调用 `deleteSession`（同事务）→ 整批一个事务；注释却承诺"不因单条失败整批回滚" | `ChatService.java:177-178`（`@Transactional` 在 `batchDelete` 上）、`:186-197`（循环 `try` + 自调用 `deleteSession`）、`:148`（`deleteSession` 也有 `@Transactional`，但自调用不生效）；注释承诺见 `:170-175` | 正常业务异常（70002）被 `catch` 掉不会标记回滚，行为符合预期；但若循环中某条抛 `DataAccessException`，事务被标记 rollback-only → **提交时整批回滚**，而接口已返回 `success=N`，前端据此删掉本地行/清本地缓存 → 出现"服务端没删、本地没了"的不一致（刷新才回来） | 去掉外层 `@Transactional`（自调用下每条语句各自提交，才能真正"逐条独立"），或把单条删除拆到独立 Bean/自注入调用；并在返回体里区分"业务失败"与"系统失败" |
| P2-2 | **P2** | **限额计数时机偏早且不回补**：`checkDailyLimit` 在消息落库**之前**就把当日计数 +1；之后被机审拦截（50002）或落库失败，Redis 计数不会回退 | `ChatService.java:391`（`checkDailyLimit` 在 `:412` insert 之前）、`:396-399`（拦截抛异常）、`:536-547`（`redis.opsForValue().increment` + 抛 70003）；限额开关默认关（`ChatProperties.java:31`） | 一旦打开限额，被风控误伤/网络失败的消息也占额度，用户更容易撞上"今天发不了"；把风控误伤和限额误伤叠加，正是当初关掉它的原因（`ChatService.java:520-523`） | 计数改到落库成功后；或在 `catch` 到 `RISK_BLOCKED`/发送失败时 `DECR`（并保留 Redis 挂掉时放行的现有策略 `:550-553`） |
| P2-3 | **P2** | **迁移脚本新增的两个索引与真实查询路径不匹配**：脚本注释说"列表查询按 (user_a,user_b) 过滤后再排 deleted"，但代码里 SQL **不带 deleted 条件**，过滤是在 Java 流里做的 → 新索引不会被用来过滤 | `sql/migration-chat-session-delete.sql:39-51`（加 `idx_session_user_a_del`/`idx_session_user_b_del`）、`sql/schema.sql:269-270`；`ChatService.java:214-217`（SQL 只有 `user_a=? OR user_b=?` + `order by last_msg_at`）、`:221-226`（内存过滤）、`:279-281`（未读同样内存过滤） | 索引白占写入与空间开销；会话量大时"我的未删除会话"仍要全量取回内存再筛；后人读脚本会误以为下推过 | 要么把 `deleted_a=0`（我侧）下推到 SQL，配合现有索引；要么删掉这两个索引，避免误导 |
| P2-4 | **P2** | **半死连接不会被摘除**：`sendQuietly` 发送失败只记日志、不注销；`unregister` 只在 `afterConnectionClosed` 触发 → 僵尸 session 常驻本机 map，每次推送都尝试一次注定失败的发送 | `ChatPushService.java:151-163`（`catch` 仅 `log.warn`，无 `unregister`/`close`）、`:63-72`（只有 `afterConnectionClosed` 会调）、`ChatWebSocketHandler.java:108-114` | 内存与推送开销随僵尸连接累积；`/chat/status` 的 `localSessions` 虚高（`ChatService.java:490`）会干扰排查；长时间运行后日志噪声大 | `sendQuietly` 捕获异常时调用 `unregister(userId, session)` 并 `session.close()`；或加定时清理（对比 `lastPongAt`/在线 TTL） |
| P2-5 | **P2** | **历史分页这一条入口没过 `normalizeMessage()`**，与注释/文档的"任何进入 store 的消息都过这一道"不一致 | `stores/chat.ts:269-272`（`appendMessage` 兜底归一化）、`:236-255`（归一化定义）、但 `:424-429`（`loadMore` 直接 `[...older, ...list]`）；文档口径见 `功能清单.md:242`、`README.md:987` | 本轮未找到可触发的坏数据（服务端 `chat_message.created_at` NOT NULL：`sql/schema.sql:284`，且渲染路径另有守卫：`ChatView.vue:404-405`、`utils/format.ts:159`），所以目前是**代码/文档不一致 + 防回归缺口**，不是线上故障 | `loadMore` 改为 `(await fetchChatMessages(...)).map(normalizeMessage)`，让"所有入口"名副其实 |
| P2-6 | **P2** | **"重新开启会话"会清掉对方那一侧的删除标记**：`openSession` 命中已删会话时调 `restoreDeleted`，而该 SQL 把 `deleted_a`/`deleted_b` **一起**清 0；对方只是从商品页点了一次"私聊"（没发消息），我这边已删除的会话就重新出现在列表里 | `ChatService.java:120-127`（触发点）、`ChatSessionMapper.java:46-55`（SQL 同时清两侧 + 注释说明这是有意为之） | 语义上"删除聊天记录"被我无法控制的对端动作撤销（虽然消息不会被"泄漏"——历史本来就保留给双方）；对"我不想再看到这个会话"的用户是体验倒退。注意：**对方再发消息导致恢复是产品明确要求**（`功能清单.md:342`、`sql/migration-chat-session-delete.sql:14-17`），本条只针对"对方仅打开会话" | 若采纳：`openSession` 只清**调用者自己**那一侧的标记（新增 `restoreDeletedFor(sessionId, sinceA)`），把"清双方"保留给"对方发来新消息"这条路径 |

## 6. 未核实 / 不确定

- **所有运行时行为本轮都没实测**：按本轮纪律未启动后端/前端，也**未运行** `scripts/chat-smoke.mjs`、`scripts/probe-chat-*.mjs`、`scripts/verify-chat-*.mjs`、`scripts/repro-chat-batch-delete.mjs`。因此"WS 真实投递时延、断线重连实测、批量删除真实鼠标点击、未读角标真实数值"只有**代码级证据**，脚本断言清单（如 `scripts/verify-chat-delete.mjs:4-11` 的 W1-W7）本轮未复验。
- **SQL 是否已在目标库执行未核实**：没有任何数据库连接；`sql/migration-chat-session-delete.sql` 只做了"脚本 vs `schema.sql` vs 实体"的文本一致性比对（C11），**不代表线上库已加列**。若线上库未 apply，`deleted_*` 相关读写会 90001 —— 这需要下一轮连库或跑接口确认。
- **Sa-Token `getLoginIdByToken` 的精确语义未读源码/未实测**（例如是否连带校验 `is-concurrent`/`is-share`）。就本部署配置（`application.yml:45` `timeout: 315360000`、`:48` `active-timeout: -1`）而言，"握手只校验 token→loginId 映射"与 REST 侧的差异可忽略，但结论未闭环。
- **WebSocket 建立后不再校验登录态**（token 被服务端注销/踢下线不会断已有连接，`ChatHandshakeInterceptor.java:26-42` 只管握手、`ChatWebSocketHandler.java:63-68` 只用 session 属性）：本轮只做了代码推断，**未实测**"注销后旧连接是否仍能收发"；两端客户端自身在登出时会 `disconnect`（`DefaultLayout.vue:83`、`providers.dart:444-455`），所以实际暴露面待评估，故未列入第 5 节正式问题。
- **多实例路径未实测**：跨实例 Pub/Sub 与 `instanceId` 回声跳过（`ChatPushService.java:106-138`）仅代码级阅读；单实例环境无法验证。
- **WebSocket 容器层参数未验证**：没有 `ServletServerContainerFactoryBean`（grep 0 命中），文本帧缓冲/会话空闲超时走容器默认值；按 `maxTextLength=500`（`ChatProperties.java:18`）估算不会触顶，但超长帧的真实行为未测。
- **移动端未构建/未运行**：sqflite 实际建表、水位写入时序、冷启动"缓存先渲染"、裁剪（`main.dart:24`）在真机上的表现均未验证；`chat_dao.dart:14` 那个 `m.fromServer ? createdAtMs(...) : createdAtMs(...)` 两个分支完全相同的三元表达式只是代码味道，**未确认是否有意为之**。
- **风控实际拦截效果未验证**：`ContentAuditService.auditText(content,"chat")`（`ChatService.java:393-395`）依赖 AI 服务，本轮未运行、未看模型侧实现，`RISK_REVIEW` 的弱提醒链路的真实表现未测。
- **VO 与前端 TS 接口未逐字段比对**：只抽读了本轮关心的字段（`canSend/blockedBy*/peerOnline/myRole/unread` 等）。
- **拉黑端到端未实测**：本轮只核实了后端拦截点与 VO 字段（C6），"拉黑后 WS 推送是否立即改变对方界面"未实测。
- **本文件不覆盖**：`item-member` 的"拉黑后商品互不可见"（`功能清单.md:421` 问题 #5 商品侧）——只在 C17 里作为 #11 的邻接事实提及。

## 7. 下一轮计划

1. **修 P1-1（Web 断线补拉，需改源码）**：`stores/chat.ts` 的 `ready` 分支改为"重连 → `loadSessions()` → 对当前会话按 `afterId` 增量补拉（满页/失败回退全量）"，并把 `ChatView.vue:646` 的"下拉刷新补齐"文案改成真实能力。验收：断网 30s 内让对方发 3 条 → 恢复网络后不刷新页面即可看到，且未读与消息数一致。
2. **修 P1-2（离开页面未清当前会话，需改源码）**：`ChatView` 增加 `onBeforeUnmount` 清空当前会话（或 store 暴露 `leaveSession()`），复测"人在首页/其他页面时收到消息 → 侧栏红点 +1、不误标已读"。
3. **修 P1-3（加密与 msgType，需改源码+配置）**：`ershou.crypto.chat-key` 走环境变量（缺失即启动失败）；REST/WS 两条路径都加 `msgType` 白名单；加密判据不再依赖客户端可控的 msgType。验收：新库写入的 `chat_message.content` 全部带 `v1:` 前缀（图片 URL 除外，需在建议里写清例外）。
4. **P2 归拢排期（需改源码，待 Leader 裁决优先级）**：P2-1 批删事务边界、P2-2 限额计数时机、P2-3 索引与查询不匹配、P2-4 僵尸连接摘除、P2-5 `loadMore` 归一化、P2-6 `restoreDeleted` 是否只清调用者一侧。
5. **把第 6 节转成实测证据（需服务，非改代码）**：后端 + Web dev server 起来后跑既有脚本 `scripts/chat-delete-test.ps1`、`scripts/chat-batch-delete-test.ps1`、`scripts/verify-chat-delete.mjs`、`scripts/verify-chat-batch-delete.mjs`、`scripts/probe-chat-enter.mjs`、`scripts/probe-ws-blocked-notice.mjs`，并连库确认 `deleted_a/deleted_b` 列已存在；移动端另跑一次真机/模拟器验证 `_fillGap` 与本地库。

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-14 | 收到 | Leader 指派：共享任务 `task-5`，第 1 轮只读核实，只写本文件，禁止改源码/配置/文档，禁止启动服务与运行 `scripts/*chat*.mjs` |
| 2026-10-14 | 提交 | `task-5` 标记 complete 并回报 `lead`（≤15 行）：状态 / 记录文件 / Top 发现（P1-1 Web 断线不补消息、P1-2 离开页面误标已读、P1-3 默认加密密钥与 msgType 绕过）/ 建议下一轮动作 |
| 2026-10-14 | 提醒 | **`git status` 抽查请注意**：工作区现有 6 个改动文件（`README.md`、`CacheProperties.java`、`JsonCache.java`、`ItemService.java`、`开发文档.md`、`开发日志.md`）+ 未跟踪 `scripts/audit-cache.mjs`，修改时间均为 **2026-10-13 08:59~09:14**（上一轮遗留，非本轮）；`智能体团队/` 目录整体未跟踪，无法按路径区分成员文件。chat-member 本轮零源码改动，只写 `智能体团队/05-chat-私聊IM.md`（详见 C18 证据） |
| （待 Leader 验收） | — | Leader 抽查口径见上一行 + C18；待填验收结论 |
| 2026-10-14 12:58 | 收到 | **Leader 验收结论：通过**（8 节齐全；18 条结论全带行号；无 P0——你排除的三类风险均留证：`ChatHandshakeInterceptor.java:29-41`、`ChatService.java:374-376/505-515`、`ChatSessionMapper.java:37-43`、`ChatService.java:425-428`。结论：`/ws/**` 自校验这段防线成立，我认可"无 P0"的判断）。问题 3 P1 + 6 P2 分级合理。 |
| 2026-10-14 12:58 | 收到 | **裁定 ①** P1-3（`ershou.crypto.chat-key` 默认密钥 + `msgType` 由客户端决定）**维持 P1**，但与 auth-member 的 P1-6（`PHONE_AES_KEY` 默认值）**同属"配置兜底值不得进生产"这一类**，第 2 轮**合并成一条主线**修（统一走环境变量 + 启动断言 + `msgType` 白名单）。**裁定 ②** P1-1（Web 断线不补消息）、P1-2（未读漏报）列入第 2 轮次批，修法以移动端 `_fillGap` 为基准对齐；`ChatView.vue:646` 的"下拉刷新补齐"文案若页面无此功能，按要求改文案或补功能，二选一由修复轮定。**裁定 ③** P2 六条按你的归拢排期，其中 P2-6（`restoreDeleted` 可能清对方一侧标记）**升级为优先**——涉及数据可见性，与 P0 同批复核。 |
| 2026-10-14 12:58 | 收到 | 第 6 节"需起服务"的实测项（跑 `verify-chat-*.mjs`/`chat-*-test.ps1`、连库确认 `deleted_*` 列已 apply、移动端 `_fillGap` 真机验证）由我在唯一实例上统一安排，**不要自行起服务**。 |
