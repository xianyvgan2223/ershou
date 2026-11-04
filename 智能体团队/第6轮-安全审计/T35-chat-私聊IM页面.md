# T35 · 私聊 IM 页面与 WebSocket 安全审计（只读）

| 项 | 值 |
| --- | --- |
| 任务 | T35 安全审计：私聊 IM 页面与 WebSocket（第 6 轮，只读） |
| 审计人 | `chat-member` |
| 审计时间 | 2026-10-21 11:02 ~ 11:40（Asia/Shanghai） |
| 审计范围 | Web：`/chat`（会话列表+聊天窗+消息气泡+输入区）、`/item/:id`、`/user/:id`、`/blocked`、顶栏消息红点；App：消息 Tab / 会话列表 / 聊天页 / 商品详情私聊；后端 `module/chat/**`；网关 `/ws/**` 与 `/api/v1/chat/**` 路由；两者共用的 Sa-Token 会话 |
| 方法 | **静态代码审计为主**（所有结论带 `文件:行`）；只读验证仅 1 类：MySQL `SELECT` 查绑定地址/账号（无写操作）。**未启动任何服务、未发起 IM 请求、未写任何库/缓存、未改任何源码/配置**（当时 8080/8081/8082/8083/8088、Redis 6380 均未运行） |
| 结论 | 发现 **18 个问题：P0×2、P1×5、P2×7、P3×4**。最严重：①开发/演示默认配置下 mock 验证码可登录**任意手机号**→ 账号接管即可读全部私聊；②仓库内散布明文 MySQL/Redis 密码 + 默认加密密钥 + Redis 广播频道无鉴权 → 拿到仓库/局域网即可批量冒充用户、窃听/伪造私聊。IDOR / 存储型 XSS / SQL 注入未发现（见附录 A） |

---

## ① 页面清单（页面 → 路由 → 接口/WS → 鉴权）

| # | 页面/入口 | 端 · 路由/文件 | 接口 / WebSocket | 鉴权（代码证据） |
| --- | --- | --- | --- | --- |
| 1 | 私聊工作台（左会话列表 + 右聊天窗 + 消息气泡 + 输入区） | Web `/chat`（`ershou-web/src/router/index.ts:76-80`，`views/ChatView.vue`） | `GET /api/v1/chat/sessions`、`GET /chat/sessions/{id}`、`GET /chat/sessions/{id}/messages`、`POST /chat/messages`、`POST /chat/messages/{id}/recall`、`POST /chat/sessions/{id}/read`、`POST /chat/sessions`、`DELETE /chat/sessions/{id}`、`POST /chat/sessions/batch-delete`、`GET /chat/unread`、`GET /chat/quick-replies`（`api/chat.ts:69-131`） | 路由 `requiresAuth:true`（router:79）+ 后端 Sa-Token 拦截 `/api/**`（`SaTokenConfig.java:15-28`）+ 会话成员校验 `requireMember`（`ChatService.java:505-515`） |
| 2 | WebSocket 实时通道（两端共用） | `ws://host/ws/chat?token=...`（`stores/chat.ts:52-55`；App `server_config.dart:34-40`） | 帧：`ready/ack/message/read/read-ack/recall/pong/error`；客户端发 `ping/read/send` | 握手 `ChatHandshakeInterceptor`（`ChatHandshakeInterceptor.java:26-42`） |
| 3 | 顶栏「消息」未读角标 | Web 布局（`layouts/DefaultLayout.vue:108-109`、`:174-175`） | `GET /chat/unread` | 登录态（同 #1） |
| 4 | 商品详情「私聊卖家 / 和 TA 聊聊」 | Web `/item/:id`（`views/ItemDetailView.vue:19`、`:306-307`） | `POST /chat/sessions {itemId}` | 登录 + `openSession` 校验商品存在/非自聊/拉黑（`ChatService.java:81-92`） |
| 5 | 用户主页「私聊」 | Web `/user/:id`（`views/UserProfileView.vue:48-59`；取对方第一件在售商品发起） | `POST /chat/sessions {itemId}` | 登录（同 #4） |
| 6 | 拉黑/解除拉黑（会话头部/主页/黑名单页） | Web `/blocked`（router:105-109）、`ChatView.vue:625-636`、`UserProfileView.vue:67-79` | `POST/DELETE /api/v1/users/{id}/block`（user 模块） | 登录；私聊发送侧再次校验 `blockedEither`（`ChatService.java:377-380`） |
| 7 | 删除聊天 / 批量删除（会话列表 ⋯、多选） | Web `ChatView.vue:217-234`、`:268-301`、`:515-567` | `DELETE /chat/sessions/{id}`、`POST /chat/sessions/batch-delete` | 登录 + 逐条 `requireMember`（`ChatService.java:148-165`、`:177-200`） |
| 8 | App 底部「消息」Tab / 会话列表 | App `ShellPage` Tab 1（`ui/pages/shell_page.dart:61`）→ `ChatListPage`（`ui/pages/chat_list_page.dart:15-22`） | `GET /chat/sessions`（`chat_list_page.dart:67`）；未读角标 `:535-555`；长按多选删除 `:247-336`、单条删除 `:342-390` | 登录 token 存 SharedPreferences（`core/storage/local_store.dart:12-25`） |
| 9 | App 聊天页（气泡/输入/图片/撤回/交易卡片） | App `ChatPage`（`ui/pages/chat_page.dart:25-31`） | `GET /chat/sessions/{id}/messages`（:208）、`POST /chat/messages`（:373）、`POST /chat/messages/{id}/recall`（:445）、`POST /chat/sessions/{id}/read`（:329）+ 全局 WS 帧（`core/providers.dart:120-129`、`chat_page.dart:96-103`） | 登录 + 成员校验（同 #1） |
| 10 | App 商品详情「私聊」 | App `item_detail_page.dart:146-158`、`:533`、`:674` | `POST /chat/sessions {itemId}`（`openWithItem`） | 登录（同 #4） |
| 11 | 网关 IM 入口（微服务形态） | `ershou-cloud/ershou-cloud-gateway` | `GET/POST /api/v1/chat/**` → 反代旧单体（`GatewayRoutesConfig.java:58`）；`/ws/**` → `ws://127.0.0.1:8080`（`GatewayRoutesConfig.java:55-56`） | `/api/**` 网关统一 Sa-Token 登录校验（`SaTokenReactorConfig.java:32-48`+`PublicPaths.java:19-65`）；**`/ws/**` 在网关是公开路径**，鉴权完全交给单体握手（`PublicPaths.java:57-59`） |
| 12 | 生产部署入口 | 开发文档 Nginx 示例（`开发文档.md:1818-1824`） | `/api/`、`/ws/` 均直接反代 `127.0.0.1:8080`（不经网关） | 单体 Sa-Token + WS 握手；`/ws/` 未见任何限流/日志脱敏配置 |

> 说明：IM 没有「举报」入口与「谁可以私聊我」隐私设置（全仓 grep `举报` 0 命中），这两个是功能缺口（见 F-12）。

---

## ② 漏洞表

> 等级：**P0**=可直接接管账号/批量泄密；**P1**=高（私密数据泄露、封禁绕过、可被规模化滥用）；**P2**=中（需特定条件/纵深防御缺失）；**P3**=低（加固项/信息泄露）。
> 验证状态：**静态**=仅代码证据；**只读验证**=另做了非写入的只读查询；**未实测**=服务未运行、按纪律未启动。

| ID | 等级 | 页面/接口 | 证据（`文件:行`） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| F-01 | **P0** | 登录入口（IM 鉴权前提，Web/App 通用） `/api/v1/auth/login` | `ershou-cloud/ershou-user-service/.../service/UserService.java:103-108`（`mockHit = smsMock && mockCode.equals(code)`，**无需先发验证码**直接命中）；`ershou-cloud/nacos-configs/ershou-user-service.yaml:49-51`（`sms.mock: true, mock-code: 123456`）；单体同样默认 mock：`application.yml:102-104` + `UserService.java:58-62,74-77,84-90` | 任何知道/猜到手机号的人用 **123456** 即可登录对方账号（云 user-service 连发码都不需要）→ 直接读该用户全部私聊会话/消息、冒充其发消息、拉黑他人。**IM 全部机密性归零** | 静态 | 生产/演示外环境强制 `ershou.sms.mock=false`（删掉 yml 默认 true，缺真实短信配置则启动失败）；mock 仅测试 profile + 手机号白名单；登录加设备/IP 频率限制 |
| F-02 | **P0** | 全站（IM 数据面/基础设施） | ① 明文凭据散布：`ershou-cloud/nacos-configs/{ershou-gateway,ershou-user-service,ershou-item-service,ershou-order-service}.yaml:10,12,19`、`docker-compose.yml:28,30`（`.env` 同密码）、`scripts/verify-chat-delete.mjs:27-28`、`scripts/repro-chat-batch-delete.mjs:21`、`application-local.yml:5,8,11`；② Redis 6380 映射到所有网卡：`docker-compose.yml:31-32`；③ MySQL `bind_address=*`、`root@'%'`（本轮只读 SELECT 实测，`mysql_native_password`）；④ 默认加密密钥：`ContentCipher.java:36`（chat-key 缺省 `__REDACTED_CHAT_KEY__`，yml 未配置）、`PhoneCrypto.java:20`（phone-key 缺省 `__REDACTED_PHONE_KEY__`）、`ershou-cloud/nacos-configs/ershou-user-service.yaml:48`；⑤ Sa-Token token 明文存 Redis db0：`application.yml:33-54`、`README.md:112,131`；⑥ 聊天广播频道可订可发：`ChatPushService.java:106-118`（`PUBLISH ershou:chat:channel:msg` 的 JSON 信封）、`:121-138`（`onRemoteMessage` 无签名校验，直接按 payload 的 `userId` 推送，可伪造消息/窃听全部聊天） | 攻击者得到仓库（密码/默认密钥都在库里）或接入同一 Wi-Fi 后：读 Redis `Authorization:login:token:*` → **批量冒充任意用户**；订阅读聊天广播/写 unread/online → 窃听与伪造消息；导库后用默认 chat-key 解密 `chat_message.content`、用默认 phone-key 解手机号 | 静态 + mysql.user/bind 只读验证（Redis 当时未运行，未实测连接） | 凭据全部改环境变量注入并轮换、从仓库清除；MySQL/Redis 只绑 `127.0.0.1`（或内网+防火墙）；chat-key/phone-key 强制注入（缺失启动失败）；Redis 频道消息加签名/只允许内网发布，Sa-Token token 值对客户端不可逆更佳 |
| F-03 | **P1** | 发消息（WS/REST）、建会话、WS 连接 | 唯一限额默认关闭：`ChatProperties.java:24-31`（`dailyLimitEnabled=false`）；发送链路无速率检查：`ChatService.java:373-391`（只查内容/限额开关/机审）；`openSession` 无频率限制 `ChatService.java:80-125`；WS 无连接数上限 `ChatPushService.java:32-49`；网关只对商品读限流 `SentinelGatewayConfig.java:39-47`；Nginx 仅对短信码限流 `开发文档.md:1835-1836` | 单账号（配合 F-01 零成本）可无限建会话+无限发消息：刷屏骚扰任意卖家、灌满受害者未读/会话风险等级、压 DB/Hikari/Redis/Push；发图消息还会把任意 URL 推到所有在线端 | 静态 | 用户级令牌桶（如 1 条/秒、20 条/分钟）+ 会话创建限额 + 每用户 WS 连接上限；网关/Nginx 对 `/api/v1/chat/**`、`/ws/` 限流；恢复新号限额/按信用分限制 |
| F-04 | **P1** | 消息加密与类型 | 默认密钥：`ContentCipher.java:36`（`${ershou.crypto.chat-key:__REDACTED_CHAT_KEY__}`，`application.yml:88-90` 仅配 phone-key）；类型未校验：`ChatService.java:381-389`（仅 `type==MSG_TEXT` 限 500 字）、`:409`（仅文本加密，其他类型原样存）；WS 手工组 DTO、不过 Bean Validation：`ChatWebSocketHandler.java:98-104` | 任何拿到代码者可解密聊天正文；任意登录用户对 `POST /chat/messages` 传 `msgType=2/3/999` 即让文本**明文落库**并绕过 500 字限制（WS 帧受容器 8KB 缓冲约束） | 静态 | chat-key 必须环境变量注入（无默认值、缺失启动失败）；`msgType` 白名单（1 文本 / 2 图片）；文本一律加密；WS 侧复用 `SendMessageDTO` 的 Bean Validation 与长度校验 |
| F-05 | **P1** | 会话列表预览 | `ChatService.java:414-418`（`setLastMsgText(preview(type, content))` 明文写入）、`:600`（`ChatSessionVO.lastMsgText` 原样下发）；表结构 `sql/schema.sql:253`（`last_msg_text VARCHAR(120)`） | 正文虽 AES 加密，但**每个会话最近 ≤60 字明文存在 `chat_session.last_msg_text`**；DB dump/备份泄露即可直接看到最新聊天内容（常含地址、电话、约见信息） | 静态 | 预览同样加密/脱敏，或只存摘要（如"[文本]"/"[图片]"+长度），恢复记录时再解密 |
| F-06 | **P1** | WebSocket 握手 token 传递 + 生产反代 | token 在 URL query：`ChatHandshakeInterceptor.java:50-59`、`stores/chat.ts:52-55`、`server_config.dart:34-40`；Nginx `/ws/` 直接反代 8080 且**无 log_format/脱敏**：`开发文档.md:1818-1824`（默认 combined 日志含完整 request URI=含 token）；token 有效期 10 年、无活动超时：`application.yml:45,48` | token 进入 Nginx access log/网关日志/浏览器调试工具；日志系统或运维任一环节泄露 = 拿到 10 年有效凭证（REST+WS 通用）→ 永久冒充该用户读全部私聊 | 静态（Nginx 默认日志行为为框架事实） | WS 改用短时效一次性 ticket（`POST /chat/ws-ticket` 换 60s token）或 Authorization 头（App）；Nginx 日志改用 `$uri` 脱敏；token 缩短有效期+轮换+设备绑定 |
| F-07 | **P1** | 已建立的 WS 连接 / 封禁、登出后的会话 | WS 建立后只认 session 属性：`ChatWebSocketHandler.java:47-60,62-68`；发消息不校验登录态/用户状态，只校验成员与拉黑：`ChatService.java:373-380`；封禁只在登录时拦截：`UserService.java:116-118`；全仓无 `kickout`/服务端强制下线（grep `kickout|StpUtil.logout` 仅 `AuthController.java:41` 自身登出） | 用户**点退出登录/被管理员封禁后**，已建立的 WS 连接仍可继续发消息、收消息；封禁治理失效，被盗 token 的连接可长期驻留 | 静态 | 每次 `send/read` 校验用户 status 与 token 是否仍有效；封禁/登出时通过 Redis 广播 kick 事件，`ChatPushService` 关闭该用户全部 session |
| F-08 | **P2** | WS 握手跨域 | `ChatWebSocketConfig.java:30-32`（`setAllowedOriginPatterns("*")`）；`ChatHandshakeInterceptor.java:26-42`（只校验 token，无 Origin 校验）；网关也把 `/ws/**` 当公开路径 `PublicPaths.java:57-59` | 任意站点可向本服务发起 WS 握手（需有效 token）；配合 F-02/F-06 的 token 泄露可被跨站利用；纵深防御缺失 | 静态 | 握手校验 Origin/Referer 白名单（配置化）；网关对 `/ws/**` 增加 ticket 校验 |
| F-09 | **P2** | 批量删除 | `BatchDeleteSessionsDTO.java:13-15`（仅 `@NotNull`，无 `@Size`）；`ChatService.java:177-200`（循环内逐条 `requireMember`→SELECT） | 登录用户 1 个请求塞 10 万 id → 10 万次 SELECT + 单事务，拖垮 DB（认证后 DoS） | 静态 | `@Size(max=100)` + 去重 + 上限校验 |
| F-10 | **P2** | 图片消息渲染（Web/App） | 图片消息 content 不做 URL 校验：`ChatService.java:381-409`；Web 直接 `<el-image :src="msg.content">`：`ChatView.vue:761-767`；App `NetImage(url: m.content)`：`chat_page.dart:933-943` | 发送方可把 `http://192.168.x.x/...`、`http://127.0.0.1:port`、`data:` 等任意 URL 作为图片消息 → 接收端浏览器/App **主动请求内网地址（客户端侧 SSRF/探测）**、追踪像素获取 IP 与在线时间、超大 data URL 消耗内存 | 静态 | 服务端校验图片 URL：只允许本站 MinIO 域/白名单 host + 路径；建议图片走服务端代理/签名短链 |
| F-11 | **P3** | 撤回接口 | `ChatService.java:443-451`：先 `selectById` 抛"消息不存在"（PARAM_ERROR），再 `requireMember` 抛 `CHAT_NOT_FOUND` | 可按自增 id 探测"某条消息是否存在"（不泄露内容），可推断平台消息量；结合递增 id 可枚举规模 | 静态 | 先查会话归属再判消息是否存在，或统一错误码；对外 id 用雪花/UUID 或加扰动 |
| F-12 | **P2** | 风控/拉黑/隐私 | 机审仅关键词黑名单：`KeywordContentAuditService.java:26-41`、`application.yml:105-109`（可被空格/同音/表情拆分绕过）；**无举报入口**（全仓 grep `举报` 0 命中）；**无「谁可以私聊我」**（`功能清单.md:250`、`:427`；PRD `FR-UP-06` 为 P0 隐私设置）；拉黑仅拦截发送/建会话（`ChatService.java:377-380`、`:90-92`），不影响商品可见性 | 站外引流/辱骂/诈骗内容可通过改写绕过机审；被骚扰者无举报通道；陌生人可无限发起会话（叠加 F-03 即刷屏）；攻击者还可用可疑词把**共享会话 riskLevel 永久抬高**（`ChatService.java:419-421`） | 静态 | 补举报接口+审核队列；补隐私开关（all/仅交易对象/仅认证用户）；风控改评分模型+频控；风险等级只升不降需审核复位 |
| F-13 | **P2** | WS 连接资源 | `ChatPushService.java:32-49`（`localSessions` 无上限、无每用户/IP 连接限制）；`:151-163`（发送失败只 `log.warn`，不 unregister/close）；`ChatWebSocketHandler.java:108-114`（仅正常 close 才摘除） | 单账号可开大量连接（文件句柄/内存 DoS）；半死连接长期驻留，推送持续做无效发送（日志/CPU 放大）；`/chat/status` 还会暴露本机连接数 | 静态 | 每用户/每 IP 连接上限、空闲超时、发送失败即摘除并计数熔断 |
| F-14 | **P2** | 移动端传输层 | App 永远用 `ws://`+`http://`：`server_config.dart:27-40`；`chat_socket.dart:8`；Android `usesCleartextTraffic="true"`：`AndroidManifest.xml:15` | 校园公共 Wi-Fi 下可被中间人嗅探：token（URL query）、全部聊天明文、图片 URL → 隐私泄露与账号接管 | 静态 | Release 强制 https/wss + 证书校验、关闭明文；WS 走 wss 后 token ticket 化 |
| F-15 | **P3** | 本地存储 | App 聊天缓存明文 SQLite：`chat_database.dart:57-74`（`content TEXT` 无加密）、`chat_dao.dart:13-31`；token 明文 SharedPreferences：`local_store.dart:12-25`；Web token 存 localStorage：`request.ts:35-43`；Web 无 CSP：`ershou-web/index.html:1-16` | 设备 root/备份、Web 任一 XSS 即可读取全部聊天记录与 token；无 CSP 放大了 XSS 影响面 | 静态 | SQLite 加密（SQLCipher）或系统安全存储；token 用安全存储/httpOnly Cookie；加 CSP 与 `X-Content-Type-Options` |
| F-16 | **P2** | 微服务网关 actuator（含 IM 链路） | 依赖：`ershou-cloud-gateway/pom.xml:64-67`；暴露 `gateway` 端点：`ershou-cloud/nacos-configs/ershou-gateway.yaml:49-53`；仅要求登录、无管理员角色校验：`PublicPaths.java:19-65`+`SaTokenReactorConfig.java:32-52`+`AuthGlobalFilter.java:29-40`；Spring Cloud **2023.0.3**（Gateway 4.1.x）原生支持 `/actuator/gateway/routes` 读与增删/refresh | 任意登录用户（配合 F-01 零门槛）可读网关路由拓扑（含内部地址）；若 actuator gateway 写端点可用，可**注入/删除路由 → SSRF、劫持 `/ws/**` 与 `/api/v1/chat/**` 流量** | 静态（服务未启动，未实测写端点） | `exposure.include` 去掉 `gateway`（或关闭 `management.endpoint.gateway`）；actuator 仅内网/仅 ADMIN 角色 |
| F-17 | **P3** | CORS | `CorsConfig.java:20-31`（`allowedOriginPatterns("*")`，`allowCredentials=false`）；单体 WS 同样 `*`（`ChatWebSocketConfig.java:32`） | 任意站点可跨域调用 API（无 Cookie 凭据，仍需 Bearer token）；当前风险低，但生产应收敛 | 静态 | 生产改成具体域名白名单 |
| F-18 | **P3** | 在线状态/排查接口 | `ChatService.java:612`（`peerOnline = online(peerId)` 任意会话对方可探测）、`:487-493`（`/chat/status` 下发 `instanceId`、本机连接数） | 任何能建会话的人可探测对方在线状态（作息/活跃时间画像）；instanceId 属内部信息（低） | 静态 | 在线状态加"仅交易中可见/延迟聚合"；`/chat/status` 仅管理员/内部 |

---

## ③ Top 3（建议优先处理）

1. **F-01（P0）mock 验证码可登录任意手机号 → IM 账号接管**
   云 user-service 在 `mockHit = smsMock && "123456".equals(code)` 时**连发码都不用**（`UserService.java:103-108`），而 `ershou-cloud/nacos-configs/ershou-user-service.yaml:49-51` 与单体 `application.yml:102-104` 默认 `mock: true`。攻击链：知道手机号 → 登录 → `GET /api/v1/chat/sessions` 拿全部会话 → `GET .../messages` 读全部私聊（接口鉴权在此形同虚设）。
   **修法**：删除 mock 默认值（生产缺真实短信配置则启动失败）；mock 仅测试 profile 且配手机号白名单；登录加设备/频率限制。

2. **F-02（P0）仓库明文凭据 + 默认加密密钥 + Redis 广播频道无鉴权 → 批量冒充与聊天窃听**
   仓库内多份 yml/脚本/文档含 `__REDACTED_DB_PASSWORD__`（DB/Redis/MinIO 同一密码）；MySQL `bind_address=*` 且 `root@'%'`（只读实测）；Redis `6380` 映射所有网卡（`docker-compose.yml:31-32`）；Sa-Token token 明文在 Redis db0（`README.md:112`）；`chat-key`/`phone-key` 均有默认值（`ContentCipher.java:36`、`PhoneCrypto.java:20`）；`ChatPushService.java:106-138` 的广播频道无签名，能 `PUBLISH` 即可伪造/窃听消息。
   **修法**：凭据改环境变量并轮换、从仓库清除；DB/Redis 改只绑 127.0.0.1；加密密钥强制注入；Redis 频道发布加内网 ACL/签名；评估给 token 加设备绑定。

3. **F-03（P1）IM 全链路零频率限制 → 刷屏骚扰与资源 DoS**
   新号限额默认关闭（`ChatProperties.java:31`），发送/建会话/WS 连接均无限流，网关只限商品读 QPS（`SentinelGatewayConfig.java:39-47`），Nginx 只限短信码（`开发文档.md:1835-1836`）。配合 F-01，攻击者可无成本批量骚扰任意卖家、灌未读红点、压垮 DB/推送。
   **修法**：用户级令牌桶 + 会话/连接上限 + 网关/Nginx 对 chat/ws 限流；恢复可配置的新号限额。
   > 紧随其后应处理 **F-04（默认 chat-key + msgType 绕过加密，数据机密性）** 与 **F-06（token 进 Nginx 日志，10 年凭证泄露）**。

---

## 附录 A：已确认的安全设计（这些点未发现问题，附证据）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 未登录访问 IM | 全部 `chat` REST 需登录：`/api/v1/chat/**` 不在 Sa-Token 白名单；网关侧同样要求登录 | `SaTokenConfig.java:15-28`；`ChatController.java:21-29`；`PublicPaths.java:19-65`；`SaTokenReactorConfig.java:32-48` |
| WS 无 token 握手 | 拒绝（HTTP 401）：无 token / token 无效都不放行 | `ChatHandshakeInterceptor.java:29-39` |
| **IDOR（读他人会话/消息）** | **未发现**：所有会话/消息/删除/已读/撤回接口都先 `requireMember(sessionId,userId)`；"不存在"与"非参与者"统一返回 70002，形不成探测 oracle；收件人由会话推导（不能指定任意用户） | `ChatService.java:505-515`、`:511-512`、`:374-376`、`:436`；`ChatWebSocketHandler.java:80-84`、`:98-104` |
| 会话 ID 可枚举 | 递增 ID 可枚举但无法越权读取（统一错误）；仅消息 id 存在性在撤回接口有 oracle（F-11，低危） | 同上 |
| 存储型 XSS | **未发现**：聊天正文/预览/昵称全程插值转义（无 `v-html`）；Flutter 用 `Text`；图片 URL 只进 `img src`（不执行脚本）；图片上传经扩展名+大小+重编码为 JPEG，不存在 SVG/HTML 上传型 XSS | `ChatView.vue:728-793`、`:549`；`chat_page.dart:933-951`；全仓 grep `v-html` 仅 `ItemCard.vue:125`（非聊天）；`MinioFileStorageService.java:61-95` |
| SQL 注入 | **未发现**：全部 MyBatis-Plus Wrapper 参数化；`LIMIT` 拼接的是 int（已 `Math.min(size,maxHistory)`） | `ChatService.java:352-364` |
| 被风控拦截消息泄露 | 拦截消息 `blocked=1`，仅发送方历史可见，接收方查询过滤；且拦截时不推送 | `ChatService.java:354-357`、`:393-400` |
| 撤回越权 | 仅发送方、仅 2 分钟内、幂等 | `ChatService.java:443-459` |
| 拉黑是否可绕过发送 | 每次发送都实时查 `blockedEither`（任一方拉黑即拒），不依赖缓存 | `ChatService.java:377-380`；`UserBlockService.java:78-85` |
| 敏感信息（手机号/位置） | 聊天 VO 不含手机号/收货地址；只含昵称/头像/信用/身份徽章/在线态；未见位置坐标 | `ChatSessionVO.java:12-46`；`ChatService.java:593-636` |
| 二进制帧 / 畸形帧 | 只处理 Text 帧，binary 帧由 `TextWebSocketHandler` 忽略；畸形 JSON 被 catch 后回 error 帧，不崩溃 | `ChatWebSocketHandler.java:62-96` |
| 错误信息泄露 | 全局异常处理器只返回统一错误码，不返回堆栈/SQL；`BizException` 仅返回业务文案 | `GlobalExceptionHandler.java:18-54` |
| 网关伪造身份头 | 网关转发前**删除客户端自带 `X-User-Id`**，防伪造身份 | `AuthGlobalFilter.java:29-40` |
| CORS 凭据 | 网关 CORS `allowCredentials=false`（API 用 Bearer 头），无 Cookie CSRF | `CorsConfig.java:20-31` |

## 附录 B：未核实 / 未实测（本轮限制）

1. **动态验证全部未做**：审计时 8080/8081/8082/8083/8088 与 Redis 6380 均未运行（`Get-NetTCPConnection` 实测），按"只读、不启服务"纪律**未启动任何服务**，因此 WS 握手/IDOR/限流等行为均为静态代码结论；F-16 的 actuator 写端点、F-01 的登录链路未实跑。
2. **Redis 暴露面为条件性**：`ershou-redis` 容器当时未运行（Docker daemon 未启动，`docker ps` 失败），`6380` 当前 FREE；F-02 的 Redis 部分基于 `docker-compose.yml` 配置推断，未实测外部连接。
3. **Sa-Token `getLoginIdByToken` 的精确语义**（是否校验冻结/活跃超时）未读 Sa-Token 源码；本部署 `active-timeout:-1`、`timeout:315360000`，对结论影响有限。
4. **网关 actuator 写操作**（Spring Cloud Gateway 4.1 `GatewayControllerEndpoint` 是否本版本启用 POST/DELETE routes）未实测，需服务启动后 1 次只读确认（`GET /actuator/gateway/routes`）。
5. **未审计范围**：admin/platform/map/cart 等非 IM 模块（其他成员负责）；仅在与 IM 链路相交处引用（Sa-Token、网关、文件上传、用户封禁）。
6. **移动端真机行为未验证**（App 未构建/安装本轮）；F-14/F-15 为代码级结论。

## 附录 C：只读验证记录（可复核）

| 操作 | 结果 |
| --- | --- |
| `grep -r "v-html" ershou-web/src` | 仅 `components/ItemCard.vue:125`（ES 高亮，非聊天），聊天组件 0 处 |
| `grep -r "举报" --include=*.{vue,ts,java,dart}` | **0 命中**（无举报功能） |
| `SELECT @@bind_address, @@port`（root，只读） | `bind_address=*，port=3306`（监听 `::`→所有网卡） |
| `SELECT user,host,plugin FROM mysql.user`（只读） | `root@'%'`、`root@'localhost'`、`ershou@'%'`、`ershou_user/it/order@'%'` 均存在（远程可用） |
| 端口探测 | `8080/8081/8082/8083/8088/6380` 全部 FREE（服务未运行，故未做动态请求） |
| `docker ps` | Docker daemon 未运行（Redis/MinIO/ES 容器均未起），未对容器做任何操作 |

> 本轮未修改任何源码/配置/数据库，未发送任何私聊消息。
