# T33 安全审计 · 地图 / 面交点 / 收藏 / 面交页面

| 项 | 值 |
| --- | --- |
| 负责人 | `map-member`（只读审计，**未修改任何源码/配置/DB**） |
| 任务 | 第 6 轮 T33：地图/面交点/收藏/面交相关页面的安全审计 |
| 审计对象 | Web：`views/MapView.vue`、`components/{AmapCampusMap,CampusMap,PointPickerDialog,ItemCard,MeetupBookingDialog}.vue`、`views/{FavoritesView,OrdersView,ItemDetailView,ChatView,CartView,PublishView}.vue`、`views/console/PlatformConsolePoints.vue`（代卖点管理）；App：`ui/pages/{map_page,point_picker_page,items_by_point_page,meetup_orders_page,item_detail_page}.dart`、`ui/widgets/map_*.dart`；后端：`module/map/**`、`module/cart/{Favorite*}`、`module/order/{OrderController,OrderService}`、`module/admin/*`（点位审核/清理）、`module/platform/controller/PlatformAdminController`（代卖点管理）；云版：`ershou-cloud-gateway/**`（路由/鉴权/CORS/限流，`/api/v1/map/**` 反代）、`ershou-order-service/**`、`ershou-item-service/.../InnerItemController`、`ershou-cloud-common/.../web/**`、`docker/docker-compose.yml` |
| 方法 | **静态代码审计为主**；只读探测尝试时运行环境已全部停机（8080/8081/8082/8083/8088/8848 均未监听、Docker 未运行 → 探测连接失败），因此**未执行任何 PoC、未发起任何写操作/DoS**；历史运行证据引用第 5 轮联调产物 `.build/cloud/demo-result.json`（2026-10-19 13:42，11/11 PASS） |
| 时间 | 2026-10-21 |
| 状态 | 已完成 |
| 结论 | 共 **10 条**：**P1 × 1、P2 × 4、P3 × 5**。最危险的是**移动端地图 WebView 存储型 XSS**（面交点名称未转义直入 `innerHTML`，任意登录用户可投放、全体 App 用户打开地图即触发）；其次是**面交点位置隐私**（含用户创建点位的精确经纬度/名称/指引对游客公开）与**高德链路对外暴露**（匿名 `amap-html` 反射 XSS + JS key/安全密钥下发、匿名 `regeo` 代理可耗尽配额）。云版"直连服务端口伪造 `X-User-Id`"导致的**他人面交单 IDOR / 库存篡改**已由 T34（SEC-ORD-01/02/10）提出，本报告不重复编号，仅在 ④⑤ 中补面交页面视角证据 |

> **等级口径**：P1 = 可直接利用且影响面广/数据安全（XSS 影响全体客户端）；P2 = 需特定条件或可被滥用（配额、隐私、内容注入面）；P3 = 信息面/健壮性/环境加固。
> **验证状态口径**：`静态确认` = 依据代码路径与类型/校验逻辑成立，未做运行时验证；`只读探测失败(环境停机)` = 尝试请求但服务未启动；`历史运行证据` = 引用第 5 轮已归档的运行产物。行号基于审计当日工作区代码（`E:\ershou`）。

---

## ① 页面清单（页面 → 路由 → 接口 → 鉴权）

### 1.1 Web（`ershou-web`）

| # | 页面 | 路由 / 入口 | 主要后端接口 | 鉴权结果（静态） |
| --- | --- | --- | --- | --- |
| W1 | 校内地图页（双模式底图 + 侧栏 + 焦点模式） | `/map`（`router/index.ts:45-48`，公开） | `GET /api/v1/map/points`；`GET /api/v1/trade-points`；`GET /api/v1/items`（侧栏 60 件，历史遗留）；`GET /api/v1/items/{id}`（`?itemId=` 聚焦） | **公开读**：单体白名单 `/api/v1/map/**`、`/api/v1/trade-points/**`（`SaTokenConfig.java:18`）、`/api/v1/items*`（`:25`）；网关同等放行 GET（`PublicPaths.java:40,62-64`） |
| W2 | 地图组件（高德 / Leaflet 降级） | `components/AmapCampusMap.vue`、`CampusMap.vue`（无自请求，仅渲染） | — | 不涉及（点位名已 `escapeHtml`：`AmapCampusMap.vue:192,223`；`CampusMap.vue:116,121`） |
| W3 | 选点弹窗（新建/选已有点位、附近、高德候选） | 发布页 `/publish`（`PublishView.vue:371`）、私聊页 `/chat`（`ChatView.vue:850`）、商品编辑弹窗 | `GET /map/points`、`GET /trade-points`、`GET /trade-points/nearby`、`GET /map/regeo`（匿名）；`POST /trade-points`、`POST /trade-points/search-hit`、`GET /trade-points/my-points`（需登录） | 写接口 Controller 内 `StpUtil.checkLogin()`（`MapController.java:60,68,76,84`）；其余读接口**公开**（`SaTokenConfig.java:18`） |
| W4 | 我的收藏 | `/favorites`（`router/index.ts:94-97`，`requiresAuth`） | `GET /api/v1/favorites`；`DELETE /favorites/{itemId}`；列表内 `ItemCard` 收藏切换 → `POST/DELETE /favorites/{itemId}` | 需登录（白名单不含 `/favorites`；`SaTokenConfig.java:15-28`）；仅本人数据（`FavoriteController.java:22-40`） |
| W5 | 我的面交（买/卖双 tab、接受/拒绝/取消/成交） | `/orders`（`router/index.ts:88-91`，`requiresAuth`） | `GET /meetup-orders`、`/todo-count`、`GET /{id}`；`POST /{id}/accept|reject|cancel|confirm`；`PATCH /{id}/point` | 需登录；服务端参与者校验（`OrderService.java:689-698`），卖家专属动作二次校验（`:482-484,:542-544,:829-830`） |
| W6 | 约面交弹窗（单品/购物车合并） | 商品详情 / 购物车（`MeetupBookingDialog.vue`） | `POST /meetup-orders`（`MeetupBookingDialog.vue:59-71` 只传 remark） | 需登录；**买家传的 pointId/timeSlot 被服务端忽略**（`OrderService.java:192-194,201`） |
| W7 | 商品详情（「在地图上看」「约面交」「收藏」入口） | `/item/:id`（`router/index.ts:51-54`，公开） | `GET /items/{id}`（公开）；`POST /meetup-orders`、`POST/DELETE /favorites`、`GET /meetup-orders?role=buyer&itemId=`（登录） | 读公开；写需登录 |
| W8 | 私聊「交易卡片」改面交地点 | `/chat`（`router/index.ts:76-79`，`requiresAuth`） | `PATCH /meetup-orders/{id}/point`（`ChatView.vue:75`） | UI 仅卖家可见（`ChatView.vue:663` 前 `isSeller &&`）；服务端再校验仅卖家（`OrderService.java:829-830`）→ **双保险** |
| W9 | 平台运营台 · 代卖点管理 | `/console/platform/points`（`router/index.ts:188-191`，`requiresAuth+requiresAdmin`） | `GET/POST/PUT/DELETE /platform/admin/points`、`POST /points/{id}/status` | 管理员：每个方法首行 `requireAdminId()`（`PlatformAdminController.java:55-86`） |
| W10 | 后台审核工作台 · 点位审核 / 空点位清理（无独立页面） | `/admin/audit`（`router/index.ts:123-126`） | `POST /admin/trade-points/{id}/approve|reject|status`；`POST /admin/trade-points/cleanup-empty` | 管理员：`requireAdminId()`（`AdminAuditController.java:105,112,119`；`AdminManageController.java:41-44`） |

### 1.2 移动端（`ershou_app`，无 URL 路由，按入口列出）

| # | 页面 | 入口 | 主要接口 | 鉴权结果（静态） |
| --- | --- | --- | --- | --- |
| A1 | 校内地图页（WebView 承载后端下发 HTML） | 底部 Tab（navIndex=2） | WebView 加载 `GET /api/v1/map/amap-html`；页内 HTML 自行 `fetch /api/v1/map/points`（`AmapPageController.java:336`）；Flutter 侧 `GET /trade-points/nearby`（`api_repository2.dart:184`） | 地图 HTML 与点位接口**匿名公开**（`PublicPaths.java:40`；`SaTokenConfig.java:18`）；WebView `JavaScriptMode.unrestricted` + `FlutterBridge`（`map_page.dart:183-186`）→ 见 SEC-MAP-01 |
| A2 | 选点页（新建点/选已有点、逆地理候选） | 发布页 `publish_page.dart:825`、购物车 `cart_page.dart:159`、私聊 `chat_page.dart:690`、面交单 `meetup_orders_page.dart:253` | WebView `GET /map/amap-html?pickable=true&wantRegeo=true`；`POST /trade-points`、`POST /trade-points/search-hit`；选点页自身 `GET /map/points` | 写入需登录（`MapController.java:68,76`）；HTML 与候选公开 |
| A3 | 点位商品页 | 地图点位卡片「看全部」 | `GET /items?pageNum=1&pageSize=50`（并按 `preferPointName` 前端过滤，遗留实现） | 公开读 |
| A4 | 我的面交（买/卖、接受/拒绝/确认/改点） | 个人中心 | `GET /meetup-orders`、`POST /{id}/accept|reject|confirm`、`PATCH /{id}/point`、`GET /meetup-orders/todo-count`（`api_repository2.dart:333-429`） | 需登录（网关 `PublicPaths` 不放行）；服务端参与者/卖家校验同 W5 |
| A5 | 商品详情（约面交 / 在地图上看） | 首页/搜索/地图点开 | `POST /meetup-orders`；地图聚焦 `MapFocus` → 地图页 | 写需登录；读公开 |
| A6 | 「我附近」窗口 / 点位商品小窗（纯 UI） | 地图页 | 无直接请求（复用 A1 数据） | 不涉及 |

> 说明：W3/W4/W6/W8 与 A2/A4 的接口是同一批后端接口；**收藏**在移动端也有入口（`item_detail_page.dart` 收藏按钮 → `/favorites`），鉴权同 W4。

---

## ② 漏洞表

### P1（高）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **SEC-MAP-01** | **P1** | 移动端地图/选点 WebView（数据源：`GET /api/v1/map/points` 与 `POST /api/v1/trade-points`） | **注入点**：`AmapPageController.java:446-448` 用字符串拼接写 `innerHTML`，名称**未做任何转义**：`el.innerHTML = ... '<div class="label">' + (p.name || '') + '</div>'`；数据来自 `:336` 页内 `fetch('/api/v1/map/points')`（以及 Flutter 传入的 `mapPoints` `:428`）。**输入面**：面交点名称仅校验非空 + 长度 ≤30（`PointSuggestDTO.java:14-15`；`TradePointService.java:334-337`），**无字符/HTML 过滤**，直接入库（`:340`）；用户创建**免审核**（`:353`）。**执行环境**：App WebView 允许任意 JS 并暴露 JS 通道（`map_page.dart:183-186`、`point_picker_page.dart:93-95`）；公开可达（网关 `PublicPaths.java:40`；单体 `SaTokenConfig.java:18`）。Web 端同类渲染已转义（`AmapCampusMap.vue:192,223`）→ **移动端是唯一未转义渲染处** | 任意登录用户创建名称含 HTML 的点位（≤30 字足够，如 `<svg onload=...>`），再发布/关联一件商品使该点位进入地图（`mapData` 只画有商品点位，`TradePointService.java:186`）→ **所有打开地图/选点页的 App 用户 WebView 自动执行任意 JS**（存储型、无需交互）。攻击者可调用 `FlutterBridge.postMessage` 伪造 `select/pick` 事件（UI 欺骗/诱导选点）、导航 WebView 到外部钓鱼页（App 未做 URL 白名单）、读取/篡改 WebView 内点位数据 | **静态确认**（运行环境停机，未做 PoC；注入/渲染路径与转义缺失均为代码级事实） | ①移动端 HTML 对 `p.name`/`p.guide` 统一转义（复用 Web 端 `escapeHtml` 逻辑或 `document.createTextNode`），禁止字符串拼 `innerHTML`；②后端对点位名称/指引做 HTML 实体转义或字符白名单（如禁止 `<>&"'`），并补 `@Pattern/@Size`；③给 WebView 页面加 CSP `default-src 'self' https://webapi.amap.com; object-src 'none'`；④`/amap-html` 加 App 专用一次性签名/nonce，禁止裸 URL 匿名加载（同时缓解 SEC-MAP-02） |

### P2（中）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-MAP-02 | P2 | `GET /api/v1/map/amap-html`（App 地图/选点页的 HTML；网关反代旧单体） | 公开：`PublicPaths.java:40`（GET `/api/v1/map*` 放行）、`SaTokenConfig.java:18`；HTML 内联**高德 JS key 与安全密钥**：`:315` `window._AMapSecurityConfig={securityJsCode:'%SECURITY_CODE%'}`、`:333` `AMapLoader.load({key:'%JS_KEY%'...})`；`mapPoints` 查询参数**原样内联**到 `<script>`（`:428,538`），仅将 `</` 替换为 `<\/`（`sanitizeJsonArray` `:548-565`）；随后名称未转义进 `innerHTML`（`:448`）；`Cache-Control: no-store`（`:250`）但无鉴权/限频 | ①**反射型 XSS**：构造 `.../amap-html?mapPoints=[{"id":1,"name":"<img src=x onerror=...>","lng":110,"lat":25}]` 诱导受害者打开即执行（同源为后端/网关 origin，无用户 cookie，但可钓鱼/发起同源公开接口请求/伪造页面）；②**匿名获取高德 JS key + securityJsCode**（设计上 JS key 公开，但安全密钥本意"防跨站盗用"，此处无 Referer/域名限制、无频控）→ 第三方可盗用配额（图面初始化 150 万次/月）并把额度算在本项目 | **静态确认**（未请求该接口以免把 key 写入日志/报告；服务停机） | ①`mapPoints` 改为 App 侧一次性 nonce 或内部签名，网关/单体对 `/amap-html` 加登录或内部访问限制；②名称转义 + CSP（同 SEC-MAP-01）；③高德控制台给 JS key 配域名/Referer 白名单，安全密钥仅由服务端校验接口使用；④该页面加访问频控 |
| SEC-MAP-03 | P2 | `GET /api/v1/map/regeo`（选点页逆地理候选，服务端代理高德 Web 服务） | 公开：`SaTokenConfig.java:18`、`PublicPaths.java:40`；实现 `AmapPageController.java:128-146`：`@GetMapping("/regeo")` 无鉴权，用**服务端 Web 服务 key**（`amapWebKey`，`:136-139` 拼进 URL）调 `https://restapi.amap.com/...`；**无频率限制**（网关仅对 `ershou-item-get` 配 QPS，`SentinelGatewayConfig.java:42-44`；单体无限流）；失败日志打印异常信息（`:143`，异常 message 可能含完整 URL/key——静态推断，本次日志检索未命中 `restapi.amap.com`） | 匿名者可无限调用：①**耗尽基础 LBS 配额（15 万次/月）**，导致正常用户选点逆地理全部降级为空候选（功能退化/DoS）；②被当作**免费地理编码代理**（任意坐标→地址）；③SSRF 面**有限**：目标域名固定、`lng/lat` 为 `Double`（`:130`），无法改 host/协议 → 不构成通用 SSRF，但属"匿名出网代理 + 配额放大" | **静态确认**（历史运行日志无该接口失败记录，故"key 进日志"未复现；未做调用） | ①`/regeo` 要求登录并做 Redis 计数限流（复用 `RedisKeys` 前缀规范，如每用户 10 次/分钟、每 IP 上限）；②服务端加本地缓存（同坐标/网格 5 分钟）；③失败日志脱敏（只打印错误码，不打印含 `key=` 的 URL）；④确认高德 Web 服务 key 已配 IP 白名单 |
| SEC-MAP-04 | P2 | 地图页/选点页的点位数据：`GET /api/v1/trade-points`、`GET /api/v1/map/points`、`GET /api/v1/trade-points/nearby` | 公开：`SaTokenConfig.java:18`、`PublicPaths.java:40`；`MapController.java:33-37`（点位列表）、`:47-55`（附近，`radius` 夹 50~5000、`limit` ≤50 见 `TradePointService.java:81-84`）；`TradePointService.java:191-193` `list()` 走 `cachedPoints(false)` → `listByCampus(false)` **只过滤"高德搜索落库且无商品"的点位，用户创建（source=2）的 0 商品点位仍全部返回**（`:151-168`）；返回字段含精确 `lng/lat/name/guideText`（`TradePointVO.java:10-20,24`）；无 `creatorId`（未泄露提交者身份，缓解） | 游客/攻击者可拉取校区内**全部点位清单**（含用户自定义的"XX宿舍楼下/门口"等名称、指引与精确经纬度，误差约米级），并可对任意坐标用 `/nearby` 做附近探测（反复换坐标可推断某建筑内点位分布/宿舍位置）；也直接为 SEC-MAP-01/05 提供"目标清单"（`source=2` 字段可筛出用户点位） | **静态确认** | ①用户创建点位在"无商品关联"时不进入公开列表（或仅创建者可见）；②公开接口坐标降精度（3 位小数 ≈100m）或只给点位名/类型、不返回经纬度（选点下拉用 `campusId` 内服务端排序即可）；③`/nearby` 要求登录 + 限流；④运营/发布页需要精确坐标时走登录后的独立接口 |
| SEC-MAP-05 | P2 | 地图页/选点页的 `POST /api/v1/trade-points`、`/trade-points/suggest`、`/trade-points/search-hit`（面交点创建） | `MapController.java:65-71`（登录后直接建点，无限流）；`TradePointService.java:327-363`：只校验"有经纬度或像素坐标"（`:328-332`）+ 名称长度（`:334-337`），**未校验 lng/lat 范围/是否落在校区内**、`guideText` 原样入库（`:347`）、**免审核** `audit_status=1`（`:353`）、创建后 `bumpMapVersion` 使整校区缓存失效（`:359`）；DTO 无 `@Size/@DecimalMin/@DecimalMax`（`PointSuggestDTO.java:14-27` 仅 name `@NotBlank`）；全仓无点位创建限流（对照 `RedisKeys` 只有短信/发布/私聊三类限流） | 登录用户可脚本批量创建**任意坐标**（甚至校外/海上）点位：①DB 与 GEO 索引膨胀；②每次创建 bump 地图版本号 → 地图/发布页缓存反复击穿（性能抖动）；③垃圾点位污染发布下拉与"我附近"；④**是 SEC-MAP-01 存储型 XSS 的投放前提**（配合一件商品即可上地图） | **静态确认** | ①用户建点限流（如 5 个/天/人 + IP 维度）；②`lng/lat` 必须在 `campus.map_config.center`+`campusSizeMeters` 的 bounding box 内（或半径校验），`coordX/coordY` 限制在 `pixelBounds`；③名称/指引做 HTML 与敏感词过滤 + `@Size`；④对同一用户短时间内重复建点合并/拒绝；⑤空点位清理（已有每日 03:10 定时任务，`ScheduledTasks.java:236-243`）可缓解存量但非实时 |
| SEC-MAP-06 | P3 | 我的收藏页 + 收藏/取消接口 | 页面无额外防护（`FavoritesView.vue` 仅调用自身接口）；接口 `FavoriteController.java:22-40` 全程用 token 取 `userId`、**无 item 存在性/状态校验**；`FavoriteService.java:32-46` 直接 insert + `addFavCount`（唯一索引兜重复）；**无频率限制**；`GlobalExceptionHandler.java:24-26` 将未登录转 10002（不会 500） | 与 T34 **SEC-ORD-13** 同一问题（本报告从收藏页链路补证）：可对不存在/已删/被封禁的 itemId 批量写收藏行（DB 污染）、可高频 add/remove 造成写放大；**未发现刷计数漏洞**（唯一索引 + `deleted>0` 才减计数，逻辑一致）；未发现他人收藏 IDOR（接口只按 token 取本人） | 静态确认 | 同 T34 SEC-ORD-13（insert 前校验商品存在/可收藏）+ 增加收藏接口限流；本报告不重复编号 |
| SEC-MAP-07 | P3 | 网关 8088 全部接口（含地图/点位公开读） | `CorsConfig.java:21-32`：`allowedOriginPatterns=["*"]`、`allowedMethod=["*"]`、`allowedHeader=["*"]`、`allowCredentials=false`、`maxAge=3600`；注释自述"生产应收敛" | 任意站点可跨域读取**公开**地图/点位/严选接口（无凭证）；因 `allowCredentials=false` 且登录态用 `Authorization` 头（非 cookie），**不构成跨站带刷/CSRF**；风险为公开数据被第三方页面直接抓取 + 生产加固缺失 | 静态确认 | 生产收敛到具体前端域名白名单；`allowCredentials` 保持 false；对公开读接口加 Referer/频控 |
| SEC-MAP-08 | P3 | App 地图/面交链路传输层 | 网关监听明文 HTTP（`ershou-cloud-gateway/src/main/resources/application.yml:1-2`；`ershou-web/.env.cloud` 代理目标 `http://127.0.0.1:8088`）；App `api_repository2.dart:241` 地图 HTML URL 与其余接口均为 `http`（历史第 5 轮联调即 http）；`/ws` 鉴权 token 走查询参数（`PublicPaths.java:57-60` 注释"鉴权由聊天模块自己按 ?token= 处理"） | 同一局域网内可明文嗅探：登录 token（Web Authorization / App 请求）、用户实时定位坐标（App 调 `/trade-points/nearby` 上传 GPS）、面交单/聊天内容；WS token 出现在 URL 可能进代理/访问日志 | 静态确认（演示环境已知取舍） | 生产强制 HTTPS/WSS（Nginx/网关 TLS）；token 一律走 header；WS 用一次性票据而非长期 token 放 query；文档标注"演示 HTTP 不可用于真实校园网" |
| SEC-MAP-09 | P3 | 收藏页/首页/列表的 `ItemCard` 高亮渲染 | `ItemCard.vue:124-125`：`<span v-html="item.highlightTitle" />`；高亮片段来自 ES（`ItemSearchService.java:137-140` 只配 `pre_tags/post_tags`，**未显式设置 `encoder`**，依赖 ES 默认 `html` 转义）；服务端未二次消毒 | 当前依赖 ES 默认 encoder=html 保持安全；但若 ES 映射/查询改动为 `encoder=none`、或高亮来源被替换（如把用户输入直接拼 `<em>`），则"收藏页/首页"出现存储型 XSS，且 Web 端 token 存 localStorage（`utils/request.ts:35-43`）可被窃取 | 静态确认（未做 ES 配置验证；属加固项） | 后端对 highlight 做白名单消毒（仅保留 `<em>`/转义其他标签）后再下发；前端可引入 sanitize 后再 `v-html` |
| SEC-MAP-10 | P3 | 面交点公开列表的信息面 | `TradePointVO.java:21-24,31,38` 公开返回 `auditStatus`（当前恒 1）、`source`（1 官方/2 用户/3 搜索）、`itemCount/onSaleCount`；`list()` 含 0 商品点位（见 SEC-MAP-04） | `source=2` 可精确筛出"用户创建点位"（便于挑选 XSS/滥用目标，也便于爬取用户活动位置）；`auditStatus` 暴露内部字段但对当前"免审核"无实际影响 | 静态确认 | 公开 VO 去掉 `auditStatus`；`source` 仅返回给登录用户/管理端；用户点位公开策略见 SEC-MAP-04 |

> 说明：上表 P2/P3 的顺序即建议修复优先级；SEC-MAP-06 与 T34 重复，仅作页面链路补充，不参与本报告 Top3。

---

## ③ Top 3（优先修复）

### 1. 移动端地图 WebView 存储型 XSS（SEC-MAP-01，P1）
**链路**：登录用户 `POST /api/v1/trade-points` 建点名 `ff<svg onload=...>`（免审核、只限 30 字）→ 关联一件商品 → 该点位进 `/api/v1/map/points` → App 地图 WebView 的 `AmapPageController.java:448` 把 `p.name` 直接拼进 `innerHTML` → **全体 App 用户打开地图即中招**；WebView 为 `JavaScriptMode.unrestricted` 且带 `FlutterBridge`（`map_page.dart:183-186`），可伪造桥事件、跳转钓鱼页。
**修复顺序**：移动端 HTML 改为 `textContent`/统一转义（最直接一行改动）→ 后端名称/指引过滤 + `@Pattern` → WebView CSP → `/amap-html` 加 App 专用 nonce（同时收口 SEC-MAP-02）。**同类 Web 渲染已转义（`AmapCampusMap.vue:192,223`），说明是移动 HTML 漏了同一道防线。**

### 2. 面交点位置隐私：用户创建点位对游客公开精确坐标与指引（SEC-MAP-04，P2）
`GET /trade-points` 公开返回**全部**点位（用户创建、0 商品也算，`TradePointService.java:151-168`），字段含米级 `lng/lat` + 自由文本名称/指引（`TradePointVO.java:10-20`）；`/trade-points/nearby` 允许任意坐标探测（`MapController.java:47-55`）。校园场景下"宿舍楼下/某栋门口"属敏感位置，且用户建点时没有任何"是否公开"的提示。
**修复**：无商品关联的用户点位不公开；公开接口坐标降精度/或只给名称；附近检索要求登录 + 限流；发布/管理需要精确坐标时走登录接口。

### 3. 高德链路对外暴露面（SEC-MAP-02 + SEC-MAP-03，P2）
`/api/v1/map/amap-html` 匿名可用：既内联 **JS key + securityJsCode**（`AmapPageController.java:315,333`），又接受**任意 `mapPoints` 参数**并在 `innerHTML` 渲染（反射型 XSS，`:428,448,548-565`）；`/api/v1/map/regeo` 匿名、无频控地代理服务端 **Web 服务 key**（`:128-146`）→ 可被刷空 15 万次/月基础 LBS 配额，让正常用户选点功能降级。两者都是"为演示方便放开"，但都可以在不动业务逻辑的前提下用"登录 + 频控 + 参数签名 + 转义"收口。
**紧随其后**：SEC-MAP-05（面交点创建无限频/坐标不设边界，是第 1 条的投放前提，也是一次性修复可闭环的点）。

---

## ④ 已核实无问题（覆盖任务重点，结论为"安全"或"已防护"）

| # | 检查点 | 结论 | 证据 |
| --- | --- | --- | --- |
| N1 | `GET /api/v1/map/amap-status` 是否泄露高德 key | **不泄露**（只回 `configured` 与 key/密钥长度，且不下发 web-key） | `AmapPageController.java:196-225`（返回字段 `:216-224`） |
| N2 | 客户端伪造 `X-User-Id` 经**网关**提权 | **已防护**：网关在转发前无条件删除客户端同名头再按会话注入 | `AuthGlobalFilter.java:30-40`（`:32-38`） |
| N3 | 面交点"写"接口未登录可写 | **已防护**：suggest/create/search-hit/my-points 均 Controller 内 `StpUtil.checkLogin()` | `MapController.java:57-86` |
| N4 | 面交点管理接口是否校验管理员（运营台代卖点 CRUD / 点位审核 / 空点位清理） | **均已校验**：每个方法首行 `requireAdminId()` | `PlatformAdminController.java:55-86`；`AdminAuditController.java:105,112,119`；`AdminManageController.java:41-44` |
| N5 | 他人面交单 IDOR（单体接口：详情/接受/拒绝/取消/成交/改点） | **已防护**：所有读写先 `requireOrder` 校验买卖双方；卖家动作二次校验；改点仅卖家；列表/todo 均按当前用户过滤 | `OrderService.java:689-698,482-484,542-544,568-571,638-663,829-830,434-439,452-457` |
| N6 | 买家能否指定面交地点/时间（越权替卖家定） | **已防护**：前端不传（`MeetupBookingDialog.vue:59-71`）、服务端忽略 DTO 的 pointId/timeSlot，默认取商品关联点位 | `OrderService.java:192-194,199-201` |
| N7 | 面交单是否泄露对方手机号/精确住址 | **未泄露**：`OrderVO` 仅含 buyer/seller 昵称、`pointName/pointGuide`（参与者可见），无手机号/经纬度 | `OrderVO.java:9-43`；`OrderService.java:930,935-936`；`SellerVO.java:6-13` |
| N8 | 他人收藏 IDOR（按 userId/itemId 参数读取或篡改） | **未发现**：接口无 user 参数，全部按 token 取当前用户 | `FavoriteController.java:22-40`；`FavoriteService.java:57-79` |
| N9 | 收藏"刷计数" | **未发现可利用**：唯一索引兜重复（`FavoriteService.java:42-44`）、仅删除成功才 -1（`:51-54`），add/remove 前后计数一致；仅剩无限频写放大（SEC-MAP-06） | `FavoriteService.java:32-55` |
| N10 | Web 页面点位名称/指引 XSS | **已转义**：高德气泡与 Leaflet 标记均 `escapeHtml`；全仓 `v-html` 仅 `ItemCard` 1 处（见 SEC-MAP-09） | `AmapCampusMap.vue:185-221,223`；`CampusMap.vue:103-121`；grep `v-html` 结果 |
| N11 | SQL 注入（地图/面交/收藏查询） | **未发现**：全部 MyBatis-Plus `LambdaQueryWrapper` 参数绑定；本仓库地图/订单模块无用户输入拼 SQL | `TradePointService.java:152-155`；`OrderService.java:243-246`；`FavoriteService.java:49-50,58-59` |
| N12 | 面交点创建是否越权指定 `campusId`/creator | 创建者固定取登录用户（`TradePointService.java:356`）；**但 campusId 未校验存在/归属**（见 SEC-MAP-05 的坐标边界问题，非越权写他人数据） | `TradePointService.java:338-357` |
| N13 | 网关 `/api/v1/map/**` 反代路径配置 | **透明反代、路径不变**：无专门地图路由，由"其余 `/api/v1/**`"兜底到旧单体 8080（保持 `/api/v1` 前缀）；GET 类公开、非 GET 需登录后才转发；未发现路径穿越/改写风险 | `GatewayRoutesConfig.java:57-58`；`PublicPaths.java:31-33,40-42`；`SaTokenReactorConfig.java:41-51` |
| N14 | CORS 是否允许携带凭证（CSRF 面） | **`allowCredentials=false`**，登录态是非 cookie 的 `Authorization` 头；跨站可直接调用的只有公开只读接口（见 SEC-MAP-07） | `CorsConfig.java:26-28`；`utils/request.ts:70-78` |

> 与任务重点的对应：**SSRF** → N1 + SEC-MAP-03（结论：非通用 SSRF，仅固定域名代理滥用）；**key 泄露** → N1 安全、SEC-MAP-02 公开 JS key 属设计取舍需收敛；**XSS** → SEC-MAP-01/02/09；**IDOR** → N5/N6/N8 + 云版直连问题（见 ⑤）；**位置隐私** → SEC-MAP-04；**未登录可写** → N3/N4（单体安全）、云版见 ⑤；**收藏刷量** → N9/SEC-MAP-06；**管理员校验** → N4；**CORS/频控** → SEC-MAP-07/03/05。

---

## ⑤ 与其他报告的关系 & 云版"直连端口"对**面交页面**的影响（不重复编号）

1. **他人面交单 IDOR（云版直连）**：与 T34 `SEC-ORD-01` 同源机制——云版服务用 `X-User-Id` 作为身份（`UserContextInterceptor.java:13-17` → `ApiHeaderSupport.java:26-41`，自动装配见 `CloudCommonAutoConfiguration.java:41-55`），而 order-service 仅监听 `0.0.0.0:8083`、无内部签名（`ershou-order-service/src/main/resources/application.yml:5-6`）。**对本审计页面的具体影响**：任何能直连 8083 者可自带 `X-User-Id` 调用 `GET /api/v1/meetup-orders/mine`（`MeetupOrderController.java:43-48` → `MeetupOrderService.java:131-141` 只按传入 buyerId 过滤）读取**任意用户的「我的面交」列表**（商品标题/成交价/remark/买卖双方 id），并可 `POST /api/v1/meetup-orders` 以他人身份建单。**修复见 T34 SEC-ORD-01**（服务仅内网监听 + 网关签名头 + 网络策略）；本报告从地图/面交页面视角补充"泄露字段"证据，避免重复计数。
2. **`/inner/**` 无鉴权导致库存被扣**（T34 `SEC-ORD-02`）：item-service `InnerItemController.java:37-54`（`PUT /inner/items/{id}/lock?qty=` 无任何鉴权）→ 直连 8082 可把任意在售商品库存扣到 0（`ItemLockService.java:35-48`），影响地图气泡数量与面交可约性。修复见 T34。
3. **中间件默认无鉴权/默认口令**：Nacos `NACOS_AUTH_ENABLE=false`（`docker/docker-compose.yml:18-24`）、RabbitMQ `guest/guest`（`:41-46`）、Seata 控制台 7091（`:64-66`）、Sentinel 8858（`:81-83`）——与 lead《第6轮-优化建议》P0-3 重叠；对本地图/面交功能的直接影响是：可读配置中心里的 DB/Redis 密码、可向 `ershou.order.topic` 发伪造事件（`sale_count`/缓存副作用）。按 lead 文档统一修复，本报告不另编号。
4. **非安全发现（功能/集成，建议 QA/Lead 关注，不计入漏洞数）**：
   - **网关路由与新 order-service 实现不一致**：`GatewayRoutesConfig.java:53-54` 把 `/api/v1/meetup-orders/**` **全部**路由到新 order-service，但新服务只实现了 `POST /`（建单）与 `GET /mine`（`MeetupOrderController.java:37-48`）；因此经 8088 访问 `GET /meetup-orders`（列表）、`GET /{id}`（详情）、`accept/reject/cancel/confirm`、`PATCH /{id}/point`、`/todo-count` **均为 404** → Web `OrdersView/ChatView` 与 App `meetup_orders_page` 的面交操作/详情/改点**在网关链路上不可用**（走 8080 直连才正常）。建议网关只精确路由已迁移的两个路径（`GET /api/v1/meetup-orders/mine`、`POST /api/v1/meetup-orders`），其余继续反代单体（与设计 4.1 "创建/我的列表已迁移"一致）。
   - 文档/注释与实际权限不一致：`OrderController.java:76` 注释"买卖双方都能改"（实际仅卖家，`OrderService.java:829-830`），与 T34 SEC-ORD-20 同一处，易误导后续把入口开给买家。
   - 遗留实现：移动端 `items_by_point_page.dart:15,42-46` 仍"拉 50 条后按 `preferPointName` 过滤"（第 2 轮 T3 已报 P1，非安全问题但影响点位商品完整性）。

---

## ⑥ 未验证 / 限制（本次审计没做到的事）

1. **无运行时验证/PoC**：审计时 8080/8081/8082/8083/8088/8848 全部未监听、Docker 未运行（仅尝试只读 GET 探测即失败），故**所有结论为静态确认**；未做任何写入、未发起 DoS/压测、未构造 XSS 页面实际加载。
2. **未验证高德 key 的真实域名/IP 限制**：无法确认控制台侧是否已配 Referer/IP 白名单，因此 SEC-MAP-02/03 的"可盗用配额"按"未配置白名单"的最坏情况评估。
3. **未验证 SEC-MAP-03 的"key 进日志"**：仅从代码推断异常 message 可能含 URL；本次在 `.build/cloud/logs/*.log` 检索 `restapi.amap.com` 为 0 命中（历史无该失败），属未复现项。
4. **未做缓存/限流组件行为验证**：SEC-MAP-05 的"缓存击穿放大"为逻辑推断（每次建点 `bumpMapVersion`，`TradePointService.java:359`），未实测效果。
5. **图片/文件类点位封面**（`cover_url`）未在本次范围（`module/file` 属 item/file 审计）；`guideText` 超长导致 DB 报错（`varchar` 截断/500）未实测。
6. **App 侧其它页面**（非地图/面交）由 T37 `app-member` 覆盖；云版服务/中间件由 T34/T36/lead 优化建议覆盖，本报告仅交叉引用。

---

## ⑦ 参考文件（本次实际读到的关键位置）

- 地图/高德：`ershou-server/.../map/controller/{MapController,AmapPageController}.java`、`.../map/service/TradePointService.java`、`.../map/dto/PointSuggestDTO.java`、`.../map/vo/TradePointVO.java`
- 收藏：`.../cart/controller/FavoriteController.java`、`.../cart/service/FavoriteService.java`
- 面交：`.../order/controller/OrderController.java`、`.../order/service/OrderService.java`、`.../order/vo/OrderVO.java`、`.../order/dto/CreateOrderDTO.java`
- 鉴权/异常：`.../common/config/SaTokenConfig.java`、`.../common/exception/GlobalExceptionHandler.java`
- 管理端：`.../admin/controller/{AdminAuditController,AdminManageController}.java`、`.../platform/controller/PlatformAdminController.java`
- 前端 Web：`ershou-web/src/views/MapView.vue`、`.../components/{AmapCampusMap,CampusMap,PointPickerDialog,ItemCard,MeetupBookingDialog}.vue`、`.../views/{FavoritesView,OrdersView,ItemDetailView,ChatView}.vue`、`.../router/index.ts`、`.../utils/request.ts`
- 移动端：`ershou_app/lib/ui/pages/{map_page,point_picker_page,items_by_point_page,meetup_orders_page}.dart`、`lib/data/api_repository2.dart`
- 云版：`ershou-cloud/ershou-cloud-gateway/.../{config/GatewayRoutesConfig,PublicPaths,SaTokenReactorConfig,CorsConfig,SentinelGatewayConfig,filter/AuthGlobalFilter}.java`、`ershou-cloud-common/.../web/{UserContext,UserContextInterceptor,ApiHeaderSupport}.java`、`ershou-order-service/.../{MeetupOrderController,MeetupOrderService}.java`、`ershou-item-service/.../{InnerItemController,ItemLockService}.java`、`ershou-cloud/docker/docker-compose.yml`
