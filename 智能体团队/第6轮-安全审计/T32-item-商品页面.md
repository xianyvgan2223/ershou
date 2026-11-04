# T32 商品域页面安全审计（首页 / 搜索 / 发布 / 详情 / 我的发布 / 收藏）

| 项 | 值 |
| --- | --- |
| 任务 | `task-32`（第 6 轮 · 安全审计，只读） |
| 负责人 | `item-member` |
| 审计对象 | Web 商品域页面 + 单体 `ershou-server`（item/search/file/admin 只读交叉）+ 云版 `ershou-cloud/ershou-item-service` |
| 审计时间 | 2026-10-21 11:20（Asia/Shanghai） |
| 审计方式 | **静态代码审计为主**；MySQL **只读 SELECT** 佐证存量数据；**未启动任何服务**（审计时 8080/8082/Redis 均未运行）→ 未做 HTTP 动态验证 |
| 纪律 | 未修改任何源码/配置/数据库；未执行写操作、未做 DoS/压测；唯一写入 = 本报告文件 |
| 结论摘要 | **未发现可直接利用的 P0**；**P1 × 3、P2 × 10、P3 × 7**；另有「已核实无问题」12 项、跨域提示 5 项 |
| 验证限制 | 服务未运行 + 无测试账号，所有「动态验证」类结论标注为未验证；详见第 6 节 |

**等级口径**：P0=可直接接管/数据泄露/未授权写；P1=关键业务规则或访问控制可绕过、影响面大；P2=需要前置条件或影响有限，但应修；P3=加固/纵深防御/信息收集。

---

## 一、页面清单（页面 → 路由 → 接口 → 鉴权）

> 前端守卫来自 `ershou-web/src/router/index.ts:231-253`；后端白名单来自 `ershou-server/.../common/config/SaTokenConfig.java:15-28`；单体 Controller 内手工校验见 `module/item/controller/ItemController.java`。

| # | 页面 / 组件 | 路由 | 前端守卫 | 主要接口 | 后端鉴权（真实实现） | 关联漏洞 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 首页 `HomeView.vue` | `/` | 公开（router:30-35） | `GET /api/v1/items`、`GET /api/v1/categories` | 公开（SaTokenConfig:17、:25；ItemController:27-31） | SEC-ITEM-08、SEC-ITEM-13 |
| 2 | 搜索（**无独立页面**，复用首页 `?keyword=`，无 `/search` 路由） | `/` + query | 公开 | `GET /api/v1/items?keyword=`（ES 优先 `ItemService.java:128-147`，降级 MySQL LIKE `:179-183`） | 公开 | SEC-ITEM-08、SEC-ITEM-12（已缓解 XSS 单点） |
| 3 | 商品详情 `ItemDetailView.vue` | `/item/:id` | 公开（router:51-55） | `GET /api/v1/items/{id}`；卖家另有 `PUT /items/{id}`、`POST /offline`；运营 `POST /admin/items/{id}/offline` | 读公开（ItemController:42-45）；写由 Controller 内 checkLogin + 属主校验（:58/:65） | **SEC-ITEM-02**、SEC-ITEM-04、SEC-ITEM-06、SEC-ITEM-09 |
| 4 | 发布 `PublishView.vue` | `/publish` | requiresAuth（router:56-61） | `POST /api/v1/items`、`POST /api/v1/files/upload?biz=item` | 拦截器白名单按路径放行 → Controller 内 `StpUtil.checkLogin()`（ItemController:48-52）；上传 `FileController.java:26-31`（checkLogin:29） | SEC-ITEM-04/05/07/15/17 |
| 5 | 我的发布 `PublishListView.vue` | `/publish-list` | requiresAuth（router:62-67） | `GET /items/mine`、`POST /items/{id}/offline`、`POST /items/{id}/relist`、`PUT /items/{id}` | `GET /items/mine` checkLogin（ItemController:34-39）+ 按 token userId 过滤；写接口 checkLogin + 属主校验 | **SEC-ITEM-01**、SEC-ITEM-06、SEC-ITEM-07 |
| 6 | 商品编辑弹窗 `ItemEditDialog.vue`（详情页/我的发布复用） | 弹窗 | 由父页面守卫 + 后端属主校验 | `PUT /api/v1/items/{id}` | checkLogin（ItemController:58）+ 属主（ItemService.java:398-400），仅拦已售出（:401-403） | SEC-ITEM-06、SEC-ITEM-07 |
| 7 | 收藏 `FavoritesView.vue` + `ItemCard.vue` 收藏按钮 | `/favorites` | requiresAuth（router:93-98） | `GET /api/v1/favorites`、`POST/DELETE /api/v1/favorites/{itemId}` | `/api/v1/favorites` 不在白名单 → 全局拦截器 checkLogin；Controller 直接 `StpUtil.getLoginIdAsLong()`（FavoriteController.java:22-39） | SEC-ITEM-11 |
| 8 | 上传组件 `ImageUploader.vue`（发布/编辑/聊天/头像复用） | 组件 | 依赖各自页面 | `POST /api/v1/files/upload?biz=` | checkLogin（FileController:29） | SEC-ITEM-14/15/16 |
| 9 | 平台严选 `PlatformView.vue`（商品卡片复用 `ItemCard.vue`） | `/platform` | 公开（router:39-43） | `/api/v1/platform/shop/**` | 公开（SaTokenConfig:19-21） | 平台域，不在本报告（跨域提示） |
| 10 | 用户主页 `UserProfileView.vue`（卖家在售商品） | `/user/:id` | 公开（router:69-74） | `GET /users/{id}/profile`、`GET /items?sellerId=` | 公开（SaTokenConfig:27） | 用户域（跨域提示） |

**鉴权矩阵要点**：读接口（首页/搜索/详情/分类/搜索状态）公开；写接口（发布/编辑/下架/重上架/二次申请/上传/收藏）全部需要登录，且有属主或管理员校验。**未发现“未登录可写”**（见第 4 节第 3 项）。

---

## 二、漏洞表

### 2.1 P1 / P2（建议本轮或下轮修复；按行内「等级」列区分）

| ID | 等级 | 页面/接口 | 证据（`文件:行`） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-ITEM-01 | **P1** | 我的发布 / 详情页 → `POST /items/{id}/offline` → `POST /items/{id}/relist`（状态机绕过） | `ItemService.java:489-503`（offline 只拦 `status==4`，随后**无条件**置 5）；`:526-537`（relist 只放开 5/7 → 2）；`:547-550`（条件更新 `status IN (5,7)`）；`:583-595`（违规 8 的合法出口是 applyRelist → 1 待审）；`ItemMapper.java:63-65`（applyRelist 仅 `WHERE status=8`）；`ItemMapper.java:52-54`（forceOffline 置 8，运营处置） | **卖家可自行绕过审核/违规处置**：① 审核中(1) → offline → 5 → relist → 2，跳过人工审核直接上线；② 审核未通过(6) → 5 → 2，绕过驳回；③ **违规下架(8) → offline(变 5) → relist(变 2)**，绕过运营处置与「违规二次申请」审核。另外 offline 允许对**已锁定(3)** 商品下架（:497 只拦 4），`:506` 会强制作废买家已锁定的面交单 → 交易单方面取消 | 静态确认（代码路径逐行核对）；运行期未复现（服务未运行） | `offline` 增加状态白名单（仅允许本人商品 1/2/3/6 → 5，**明确拒绝 8**）；`relist` 前校验“非违规/非审核未通过来源”，或状态机改为显式迁移表；对 8 增加“不可由卖家 offline”断言 |
| SEC-ITEM-02 | **P1** | 商品详情 `GET /api/v1/items/{id}`（公开，非在售商品可枚举） | `ItemService.java:251-268`（detail 直接 `selectById`，**无 status 校验**；不存在才 404）；`ItemController.java:42-45`（公开映射，SaTokenConfig:25 白名单）；`ItemVO.java:11-54`（详情返回 description/images/price/seller.id/status/statusText 等）；云版同样：`ItemQueryService.java:83-97`、`ItemQueryController.java:46-55` | 任何人可**按自增 ID 顺序遍历**读取：审核中(1)/审核未通过(6)/违规下架(8)/草稿(0) 的商品完整内容（描述、图片、价格、卖家 id），即“未公开内容泄露 + 内容审核前的预发布信息”；同时每次访问都会计入浏览量（`:274-277`）。只读佐证：当前库存在 `id=891 status=4（已售出）` 的商品，公开详情接口按代码会返回其内容（当前无 1/6/8 状态数据，风险为潜伏态） | 静态确认 + 存量数据佐证（`SELECT id,status FROM item WHERE status<>2` → 891/4） | 详情接口增加可见性规则：`status=2` 公开；其余状态仅“本人 / 运营”可读（Controller 传 `currentUserId` 已在，Service 内判断即可）；非公开详情不计浏览、且响应裁剪描述/图片等字段 |
| SEC-ITEM-03 | **P1**（云演示环境；若公网暴露=**P0**） | 云版内部接口 `GET /inner/items/{id}`、`PUT /inner/items/{id}/lock`（:8082 直连） | `ershou-cloud/.../item/controller/InnerItemController.java:28-52`（两个接口**无任何鉴权注解/校验**）；云 item-service **无 Sa-Token 依赖、无 SaInterceptor、无 StpUtil/SaCheck 使用**（全模块 grep 0 命中；`ershou-item-service/pom.xml:9` 注释自述“不需要 sa-token”）；`application.yml:1-2` 仅设 port，**未设 `server.address`** → 默认监听全部网卡；网关只挡住 `/api/v1/**`，直连 8082 可完全绕开网关鉴权/Sentinel（`PublicPaths.java:19-66`、`AuthGlobalFilter.java:30-41`） | 任何能访问 8082 的客户端（同机/局域网）都可：① 读任意商品（含 sellerId/价格/库存）；② **无限调用 lock 扣库存**，把在售商品扣到 0/置已售出（拒绝服务/库存耗尽），且会污染 `stock`（无补偿）。第 5 轮联调时已在**未带 Authorization 头**的情况下成功调用 `lock` 返回 `data=1`（本轮未重放写操作） | 静态确认（无鉴权代码）+ 第 5 轮运行期旁证；本轮未动态验证 | ① 内部接口加服务间鉴权（内网 token/`X-Internal-Token` 或 mTLS）；② 绑定内网/回环 `server.address`，或 Docker 网络隔离只允许 order-service 访问；③ 网关屏蔽 `/inner/**`（即便不路由也应显式 404）；④ 生产环境加网络策略 |
| SEC-ITEM-04 | **P2**（高影响加固，接近 P1） | 发布/编辑图片、详情/卡片渲染（外部 URL 无校验 → 追踪像素 + 管理员浏览器 SSRF） | `ItemPublishDTO.java:40-46`（images 仅 `@NotEmpty/@Size`，**无 URL/主机/协议白名单**）、`ItemUpdateDTO.java:46-51` 同样；`ItemService.java:341-342`、`:461-464` 直接落库作 coverUrl/images；渲染点：`ItemDetailView.vue:333`、`:346`；`ItemCard.vue:87`（`el-image :src`）；管理端渲染同一数据：`AdminItemsView.vue:265`、`:363`、`AdminReviewView.vue:502`、`:642` | ① 卖家可填**任意 URL**：买家/运营浏览时浏览器自动请求 → 泄露访问者 IP/UA/时间（追踪像素），可做行为画像/社工；② 运营后台在**管理员浏览器**中加载攻击者 URL，可对内网管理面发起**客户端 SSRF**（如 `http://127.0.0.1:8848/...`、内网 IP:端口），配合图片错误/时序做端口探测；③ URL 长度无上限（单条可到 MB 级），9 条 × 大 data URI → DB/带宽放大 | 静态确认；存量数据无站外 URL（只读 `SELECT ... WHERE cover_url NOT LIKE 'http://127.0.0.1:9010/%'` → 0 行），说明**尚未被利用** | 服务端只接受“本平台上传接口返回的 URL 前缀”白名单（或改为只收 objectKey 由服务端拼 URL）；拒绝 `data:`/`javascript:`/`file:`/非白名单 host；前端 `<img>` 统一加 `referrerpolicy="no-referrer"`；图片 URL 长度上限（如 512） |
| SEC-ITEM-05 | **P2** | 发布/编辑内容审核（图片不审 + 文字匹配可绕过） | `ItemService.java:319`（发布只审 `title + description`）、`:415-419`（编辑只在文字变化时审）；`:341-342`（图片直接入库，**无图片审核**）；`KeywordContentAuditService.java:26-55`（纯 `String.contains` 子串匹配，仅 `toLowerCase`，无空格/全角/同音/变体归一化；禁售词 23、可疑词 6）；`application.yml:97`、`:99`（词表）；渲染见 SEC-ITEM-04 | 违规商品可绕过机审发布：文字用 `管 制 刀 具`/全角/形近字切分；**站外联系方式（微信/QQ/手机号）写在图片里或未被词表覆盖的文字中** → 诱导站外交易、诈骗责任与合规风险（平台“交易留校内”的信任模型被绕过）。只读佐证：当前存量标题/描述未命中“微信/电话/vx”（`SELECT ... LIKE` → 0 行），但这是词表运气而非机制保证 | 静态确认 + 只读数据佐证（当前未被利用） | 增加联系方式正则（11 位手机号、`vx/微信/QQ+号码` 模式）；图片接入 OCR/AI 审核（或至少人工抽审图片）；敏感词归一化（去空白/全半角/繁简转换）；敏感词命中日志与人工复核队列 |
| SEC-ITEM-06 | **P2** | 商品编辑 `PUT /items/{id}`（已锁定/审核中商品可改价改内容，TOCTOU） | `ItemService.java:393-403`（仅拦 `status==4`）；`:446-464`（price/images/title/stock 等直接覆盖）；`:472`（`updateById` 盲目更新）；`:475-478`（改名只“登记”进审核队列，**不阻断在售展示**，见 `ItemReviewService.java:123-141` 注释：仅入队） | 买家已**锁定(3)**/已约面交后，卖家可改价格、图片、标题 → 买家看到的商品与最终下单价/实物不一致（下单价格由订单侧按当前价取）；也可在人工审核员“看过”后立刻改名换图，审核队列来不及拦截 → 内容审核规避窗口 | 静态确认（运行期未验证） | 有进行中订单/锁库(status=3)时禁止改价与图片；改名/改图在审核通过前保持旧版本展示或先下架；至少记录“价格变更审计日志”并在详情页提示“内容已变更，请重新确认” |
| SEC-ITEM-07 | **P2** | 发布/编辑参数校验缺口（stock/negotiable/isUnique/originalPrice/categoryId/preferPointId） | `ItemPublishDTO.java:32-46`（originalPrice、negotiable、stock、isUnique、preferPointId、videoUrl **均无 `@Min/@Max/@Pattern`**；compare `conditionLevel:22-25`、`price:27-30` 有校验）；`ItemUpdateDTO.java:38-51` 同样；`ItemService.java:449-470` 直接落库；前端限制仅在 `PublishView.vue:252-298`（el-input-number min/max） | 绕过前端直接调 API 可写入：`stock=-1/2147483647`、`negotiable=5`、`isUnique=99`、`originalPrice` 负数、`categoryId` 不存在、`preferPointId` 他人/不存在点位 → 数据完整性破坏、列表展示异常、**负库存导致锁库条件 `stock>=qty` 恒失败**（商品永久不可交易）、超大库存虚假在售；非法枚举值使前端标签渲染为“未知” | 静态确认；存量数据当前正常（只读：`stock` 0–5、`negotiable` 仅 1、`is_unique` 仅 0/1、price 5–1250） | DTO 补齐约束：`stock @Min(0)@Max(999)`、`negotiable/isUnique @Min(0)@Max(1)`、`originalPrice @DecimalMin("0")`；`categoryId` 校验存在且启用、`preferPointId` 校验同校区存在；补充服务端业务校验兜底 |
| SEC-ITEM-08 | **P2** | 首页/搜索 `GET /api/v1/items`（无限流、LIKE 通配符未转义、深分页无上限） | `ItemQueryDTO.java:9-36`（keyword 无长度上限；pageNum 无上限）；`ItemService.java:179-215`（`:183` `like` 未转义 `%`/`_`；`:215` 仅夹 `pageSize≤100`，pageNum 不限制 → 大 OFFSET）；ES 深分页回退逻辑 `ItemSearchService.java:37`、`:69-72`；**item/search/file 模块无任何限流代码**（`RATE_LIMIT` 仅用于 user 验证码与 platform 领券/抢购，item 域 0 处；单体无 Sentinel） | 公开接口可被**批量爬取**全站商品（标题/描述/价格/卖家 id → 竞品分析、诈骗名单）；单字符 `keyword=%` 触发全表 LIKE 扫描；`pageNum=1000000` 造成大 OFFSET 慢查询 → DB/ES 资源消耗型 DoS；无风控观测，无法发现异常抓取 | 静态确认（未做压力验证，遵守不 DoS 纪律） | 网关/接口限流（IP+用户维度，如 Sentinel QPS/Redis 令牌桶）；keyword 长度上限（≤50）且转义 `%_`；pageNum 上限（如 ≤200）；匿名用户降级（如仅返回前 20 页）；建设访问日志与异常频率告警 |
| SEC-ITEM-09 | **P2** | 商品详情浏览量（可刷量，无去重/风控） | `ItemService.java:274-277`（每次 detail 调用 `pendingViews`）、`:283-299`（`INCR` 无任何限频/去重，匿名可刷）；持久化 `ScheduledTasks.java:175-207`（每 10 分钟回写 DB）；云版 `ItemQueryService.java:95`、`:127-136` | 浏览量可被脚本无限刷高（也可多账号刷），商品“热度”数据不可信 → 影响买家决策/平台背书；若未来按热度排序则可被刷榜；当前列表排序按 `publishedAt`（`ItemService.java:192-196`），直接影响有限。只读佐证：当前 `MAX(view_count)=8`，未被利用 | 静态确认 + 只读数据佐证（未利用） | 浏览计数去重（`itemId+userId/IP+时间窗` SETNX），匿名按 IP 限频；展示端用“XXX+ 人看过”并设上限；异常刷量监控 |
| SEC-ITEM-11 | **P2** | 收藏 `POST /favorites/{itemId}`（不校验商品存在/状态） | `FavoriteService.java:32-45`（add 直接 insert + `addFavCount`，**无 item 存在性/状态校验**；重复收藏靠 `DuplicateKeyException` 兜底）；`FavoriteController.java:29-31`（仅登录）；`ItemMapper.java:38-39`（`fav_count = GREATEST(fav_count+delta,0)`） | 可对**不存在 / 非在售（审核中/下架/违规）** 商品收藏 → 利用“是否成功/是否重复”作为商品存在性与状态探测旁路；可让隐藏商品的 `fav_count` 被多账号刷高；对非在售商品收藏在业务上无意义却污染数据 | 静态确认；只读 `MAX(fav_count)=7`（未利用） | `add` 前校验 item 存在且 `status=2`（或明确允许“历史商品但仅本人可见”）；收藏计数异步校准；异常频率限制 |
| SEC-ITEM-12 | **P2（加固）** | 搜索高亮 XSS 单点（当前已缓解，但依赖过窄） | 全仓唯一 `v-html`：`ItemCard.vue:125`（`item.highlightTitle`）；数据源唯一：ES highlight，配置 `ItemSearchService.java:138-141`（`pre_tags:["<em>"]` + `encoder:"html"`）+ `ItemSearchService.java:122-147`（`multi_match` 只回 id/total/高亮，`_source:false`）；Javadoc 明确“字段仍回 MySQL 取最新值”（`ItemSearchService.java:24-27`）；无前端 sanitize、无 CSP（见 SEC-ITEM-10） | **当前不可利用**：user 内容由 ES 做 HTML 转义，只剩 `<em>`。但防线全在“后端 ES 配置”单点：一旦①改为返回 `_source`/自拼高亮、②加新搜索源/云服务返回高亮、③ES encoder 配置回退 → 立即变 stored XSS；且 token 存 localStorage（`request.ts:35-47`）会放大为账号接管 | 已核实当前不可利用（静态）；动态未验证（服务未运行） | 前端对 highlight 做白名单 sanitize（仅允许 `<em>`，如 DOMPurify allow-list），或改为“纯文本 + 前端按关键词自行高亮”；配合 CSP（SEC-ITEM-10） |
| SEC-ITEM-10 | P2（加固） | 全站默认安全响应头缺失（CSP / X-Frame-Options / nosniff / Referrer-Policy） | 前端 `ershou-web/index.html`、`vite.config.ts` 无相关配置；后端 `common/config/*.java` 无 `addHeader`/CSP 配置（grep 无命中）；网关仅 CORS（`ershou-cloud-gateway/.../CorsConfig.java`，非安全头） | 点击劫持（无 X-Frame-Options/frame-ancestors）；MIME 嗅探；referer 泄露；**无 CSP = 任何未来 XSS 零成本执行**（叠加 localStorage token → 持久账号接管）；纵深防御缺失 | 静态确认（未做浏览器验证） | 网关/nginx 统一注入：`Content-Security-Policy`（`default-src 'self'; script-src 'self'; img-src 'self' http://127.0.0.1:9010; object-src 'none'; frame-ancestors 'none'`）、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`；token 改 httpOnly cookie 或短 TTL+refresh |
| SEC-ITEM-18 | **P2** | 图片上传“解压炸弹”（文件大小限制 ≠ 解码后内存） | `MinioFileStorageService.java:83-95`（`validate` 只校验**文件字节数** ≤5MB 与扩展名）；`:104-111`（`compress()` 先 `ImageIO.read(file.getInputStream())` **整图解码为 BufferedImage**，再判断长边/缩放）；`application.yml:21`（multipart `max-file-size: 5MB`） | 登录用户可上传 5MB 内的高压缩比图片（如超大分辨率 PNG），`ImageIO.read` 会按“宽×高×4 字节”分配内存（例：30000×30000 ≈ 3.6GB）→ 单次上传即可 OOM/GC 抖动，拖垮文件服务进而影响发布/头像/聊天图片；5MB 限制对此无效 | 静态确认（未构造炸弹文件实测，避免影响运行环境；无运行服务） | 解码前先读图片头拿宽高并限制像素总量（如 ≤ 4000 万像素 / 宽高 ≤ 10000）；优先用 `ImageReader` 采样解码（`ImageReadParam.setSourceSubsampling`）或限制 `Thumbnailator` 输入尺寸；单用户上传并发/频率限制 |

### 2.2 P3（加固 / 信息收集）

| ID | 等级 | 页面/接口 | 证据（`文件:行`） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-ITEM-13 | P3 | `GET /api/v1/search/status`（公开） | `SearchController.java:32-35`（无鉴权公开）；返回 `ItemIndexService.java:474-490`：`indexName/esAvailable/lastError/watermark/reindexing/docCount/onSaleCount`；`es.lastError()` 可能含内网地址/连接错误细节（`:479`） | 基础设施信息泄露（ES 可用性、索引名、水位、重建状态），为攻击者选时（重建期间降级 MySQL/熔断期）提供情报；`lastError` 可能泄露内网 IP/端口 | 静态确认；运行期未验证（服务未运行） | 改为管理员可见或脱敏（去掉 lastError 细节，仅布尔健康状态）；或只在内网/监控端口暴露 |
| SEC-ITEM-14 | P3 | 图片上传（本地存储降级模式） | `LocalFileStorageService.java:26-40`：仅校验“文件非空”，**无扩展名/大小/图片内容校验，不压缩**，直接 `transferTo` 且文件名强制 `.jpg`（`:33`、`:39`）；对照 `MinioFileStorageService.java:83-95`（扩展名+5MB）、`:104-122`（ImageIO + Thumbnailator） | `ershou.storage.mode=local` 时：任意类型/超大文件（受 multipart 5MB 限制，见 `application.yml:21`）可被存为公开可访问的 `.jpg` → 内容伪装、存储膨胀、异常内容分发；两模式行为不一致易于踩坑 | 静态确认（当前默认 `mode: minio`，`application.yml:116`；本地模式未启用） | 抽出公共 `validate()+compress()` 供两种实现复用；本地模式同样只收真实图片 |
| SEC-ITEM-15 | P3 | 上传接口无频率/配额限制 + 桶 public-read 永久可访问 | `FileController.java:26-31`（仅登录，无频率/配额/大小外的约束，`biz` 任意）；`MinioFileStorageService.java:44-51`（启动即建桶并设 **public-read** 策略）；`:74`（返回公网直链） | 登录用户可无限上传（存储/带宽成本攻击）；商品删除/账号注销后图片仍永久公开（数据最小化/删除权问题，且可被反查卖家历史图） | 静态确认 | 每用户/每 IP 配额 + 频率限制；对象生命周期（商品删除后异步清理）；私有桶 + 短期签名 URL（或 CDN 鉴权） |
| SEC-ITEM-16 | P3 | 上传 `biz` 参数（目录名）无白名单 | `FileController.java:27-30`（`biz` 直接透传）；`MinioFileStorageService.java:98` 与 `LocalFileStorageService.java:31` 仅 `replaceAll("[^a-zA-Z]","")` 净化 | 路径穿越已被净化阻断（文件名 UUID + 日期，无用户可控文件名）；但可写入任意字母目录前缀（如伪装成 `admin/`），造成存储目录语义混乱/误导排查 | 静态确认（无穿越） | `biz` 改枚举白名单（item/avatar/chat）校验；非法值回退 `common` 或 400 |
| SEC-ITEM-17 | P3 | 发布接口无幂等/防重 + 限额语义偏差 | `ItemService.java:302-320`（无 requestId 幂等；`:307-314` 新号限额 `INCR` 在机审**之前**，被拦截/失败也扣次数，且老账号无限制）；`ItemService.java:319`（机审）；前端重复点击 `PublishView.vue:103-152` 未禁用提交（`submitting` 有 loading，但网络重试/并发仍可重复） | 重复发布（双击/重放）产生重复商品；被禁售词拦截的尝试也占额度（限额语义=“尝试”而非“成功”）；老账号可无限发垃圾 | 静态确认 | 发布幂等键（前端 requestId + Redis `SETNX` 短 TTL）；机审通过后再计入限额；分档限额 + 新号验证码/人工审核 |
| SEC-ITEM-19 | P3 | 单体 Sa-Token 白名单**按路径不按方法**（`/api/v1/items`、`/api/v1/items/*` 对所有方法放行） | `SaTokenConfig.java:15-28`（`.notMatch("/api/v1/items", "/api/v1/items/*")` 无方法区分；`:17` 分类等亦同）；当前所有写 handler 均自带 `StpUtil.checkLogin()`：`ItemController.java:50`（发布）、`:58`（编辑）、`:65`（下架）、`:73`（重上架）、`:80`（二次申请），上传 `FileController.java:29` | **当前无未授权写**（已逐接口核对）；但防御纵深不足：未来新增 `/api/v1/items/**` 写接口若忘记手工 checkLogin，即直接未授权；白名单与 handler 实现隐式耦合 | 静态确认 | 白名单按方法限定（仅 GET 公开）或在 handler 上加 `@SaCheckLogin`；写接口统一走拦截器，不依赖每个方法手写 |
| SEC-ITEM-20 | P3 | 上传文件 EXIF/元数据与内容留存（补充） | `MinioFileStorageService.java:104-122`（Thumbnailator 重编码 → 大概率剥离 EXIF，静态推断未逐字段验证）；对象永久保留（见 SEC-ITEM-15） | 若重编码未剥离 GPS/设备信息则随图公开；图片永久留存带来隐私追踪 | **未验证**（未对真实上传文件做 EXIF 提取，服务未运行） | 上传后显式剥离 EXIF（`ImageIO` 重写/元数据白名单）+ 生命周期清理；补一条自动化断言 |

---

## 三、Top 3（按修复优先级）

1. **SEC-ITEM-01 商品状态机可被卖家绕过（P1）** — `offline` 只拦已售出（`ItemService.java:489-503`），可把「审核中(1)/审核未通过(6)/违规下架(8)」无条件改成「已下架(5)」，再用 `relist` 换回在售(2)（`:526-550`），**绕过人工审核、驳回与运营违规处置**，还能对已锁定(3)商品下架并强制作废买家订单（`:506`）。这是商品域最直接的业务规则绕过，且无需任何特殊权限。
2. **SEC-ITEM-03 云版 item-service 内部接口无鉴权且直连暴露（P1）** — `/inner/items/{id}`、`PUT /inner/items/{id}/lock` 无任何鉴权（`InnerItemController.java:28-52`），云模块无 Sa-Token（pom 无依赖/无拦截器），`application.yml:1-2` 监听全部网卡；直连 8082 可绕过网关，任意扣库存/枚举商品。演示环境即真实可利用（第 5 轮曾无 token 调用成功），生产化前必须加服务间鉴权 + 网络隔离。
3. **SEC-ITEM-02 非在售商品详情公开可枚举（P1）** — `GET /items/{id}` 不做状态校验（`ItemService.java:251-268`、`ItemController.java:42-45`），顺序 ID 即可读取审核中/驳回/违规下架商品全文与图片（`ItemVO.java:11-54`），并顺手计入浏览量；云版同缺陷（`ItemQueryService.java:83-97`）。修复成本低（Service 内加 status/属主可见性判断），收益直接。

> Runner-up：**SEC-ITEM-04**（图片任意外部 URL → 追踪像素 + 管理员浏览器 SSRF）虽评 P2，但受害者是**运营/管理员**、且无需交互，建议紧随 Top3 修复。

---

## 四、已核实“无问题 / 已缓解”清单（覆盖 Lead 指定重点）

| # | 检查项 | 结论 | 证据 |
| --- | --- | --- | --- |
| 1 | **SQL 注入** | **未发现**：item/search 模块无 `${}` 拼接 SQL；查询全部走 MyBatis-Plus 参数化 wrapper；排序为白名单 switch；`preferPointIds` 为 `List<Long>` 且 limit 50 | `ItemService.java:179-215`（`:192-196` sort switch、`:202-205` long 列表限 50）；`ItemMapper.java:17-88`（全为 `#{}` 参数）；无 `\$\{` 命中 |
| 2 | **Elasticsearch DSL 注入** | **未发现**：keyword 经 `ObjectMapper.writeValueAsString` JSON 转义后才拼入 DSL；类目/校区/卖家为 Long 直接拼（类型安全）；sort 白名单；DSL 由服务端语义校验 | `ItemSearchService.java:112-125`（term/multi_match）、`:150-156`（sort 白名单）、`:159-162`（`quote()` JSON 转义） |
| 3 | **未登录发布/编辑/下架（未授权写）** | **已防护**：所有写接口 Controller 内 `StpUtil.checkLogin()`；上传同样；无 token 无法发布/编辑/下架/重上架 | `ItemController.java:50/58/65/73/80`；`FileController.java:29`；全局拦截器 `SaTokenConfig.java:15-28` |
| 4 | **IDOR：编辑/下架/重上架他人商品** | **已防护**：每个写操作先按 id 查商品再比对 `sellerId == 当前登录用户`，不符抛 `ITEM_NOT_OWNER`；重上架有状态白名单（5/7） | `ItemService.java:398-400`（update）、`:494-496`（offline）、`:531-533`（relist）、`:588-590`（applyRelist） |
| 5 | **参数篡改 sellerId / status / 审核结果 / version（mass assignment）** | **已防护**：请求 DTO 字段白名单**不含** sellerId/status/auditResult/auditReason/version；这些字段全部由服务端从登录态/机审结果设置 | `ItemPublishDTO.java:9-46`、`ItemUpdateDTO.java:19-52`（无上述字段）；`ItemService.java:329`（sellerId 取 token 用户）、`:350-352`（status/audit 由机审决定）、`:431`（编辑后状态由机审决定） |
| 6 | **上传文件类型/大小/路径校验、MinIO 预签名** | **基本安全**：multipart 5MB/30MB；MinIO 实现校验扩展名白名单 + 大小 + 用 `ImageIO.read` 验证真实图片内容，统一重编码 jpg、强制 `image/jpeg`；路径为 `biz(字母净化)/yyyy/MM/uuid.jpg`，无用户可控文件名 → 无路径穿越；未使用预签名 URL（返回固定 public 直链） | `application.yml:20-22`；`MinioFileStorageService.java:83-95`、`:104-122`、`:71`、`:97-101`；`FileController.java:26-31`（详见 SEC-ITEM-14/15/16 的加固建议） |
| 7 | **详情泄露卖家手机号/微信等隐私** | **未泄露**：商品 VO 的 seller 只有 id/nickname/avatar/creditScore/identityFlag/shopName；无 phone/wechat/实名；公开用户主页同样无手机号 | `ItemVO.java:11-54`；`SellerVO.java:7-12`；`PublicUserVO.java:14-34`（仅昵称/头像/信用/徽章/店铺/学校校区/在售数） |
| 8 | **收藏数重复加减刷量（单账号）** | **已防护**：`favorite` 表唯一索引兜底，重复收藏捕获 `DuplicateKeyException` 不重复计数；取消仅在实际删除行数>0 时 -1 | `FavoriteService.java:32-54` |
| 9 | **CSRF** | **不适用**：认证为 `Authorization: Bearer` 请求头（非 Cookie），跨站表单无法自动携带 → 无经典 CSRF 面 | `request.ts:71-77`（请求拦截注入 header）；`application.yml` sa-token `token-name: Authorization` |
| 10 | **前端 XSS（标题/描述/留言渲染）** | **当前不可利用**：全仓仅 1 处 `v-html`（ES 高亮，服务端 `encoder:html` + 只回 id/高亮），其余均为 `{{ }}` 自动转义（标题/描述/留言）；商家/平台角标文案为静态 | `ItemCard.vue:125`（唯一 v-html）；`ItemDetailView.vue:353`（标题）、`:455`（描述）、`:473`（店铺名）；`OrdersView.vue:234`/`ChatView.vue:675`（面交留言，属订单域，同样 `{{ }}`）；`ItemSearchService.java:138-141` |
| 11 | **运营越权强制下架** | **已防护**（交叉核对 admin 域）：接口 `@Valid ReasonDTO`（原因必填）+ `adminAuthService.requireAdminId()` + 审计日志 | `AdminManageController.java:59-62`（:60 @Valid、:61 requireAdminId）；`AdminItemService.java:191-205`（:204 审计日志） |
| 12 | **商品不存在/已删除的详情** | **无穿透/无泄露**：空值哨兵防穿透，返回 30001 | `ItemService.java:251-268`（`:255-262` 空值缓存 + 404） |

---

## 五、跨域提示（不属商品域责任，供 Lead 分派）

| # | 域 | 现象 | 证据 | 建议责任 |
| --- | --- | --- | --- | --- |
| C-1 | admin（运营后台） | 后台商品列表/详情**明文展示卖家手机号**（未脱敏、无查看审计） | `AdminItemVO.java:31`（`sellerPhone`）；`AdminItemsView.vue:289`、`:402`；`AdminReviewView.vue:691` | admin-member |
| C-2 | auth（登录注册） | 第 1 轮已确认 **SMS mock 固定码 123456**（生产级 P0）：可批量注册马甲号 → 配合商品域发布/收藏/浏览刷量放大 SEC-ITEM-08/09/11 | `智能体团队/01-auth-登录注册与会话.md`（P0-1）；`application.yml` 中 `ershou.sms.mock` | auth-member / Lead |
| C-3 | cloud-infra | Nacos 配置模板**明文提交 DB/Redis 密码**（含 ershou_item 账号密码） | `ershou-cloud/nacos-configs/ershou-item-service.yaml:10`、`:19` | platform-dev / chat-member |
| C-4 | chat（IM） | 聊天消息图片 `:src="msg.content"`，外部 URL 同源 SSRF/追踪风险；本轮未审计 chat 域 | `ChatView.vue:763` | chat-member |
| C-5 | gateway（云） | 网关只保护 `/api/v1/**`，服务直连端口（8081/8082/8083）无 mTLS/内网 token；`/inner/**` 未在网关显式拒绝 | `PublicPaths.java:19-66`；`AuthGlobalFilter.java:30-41`；SEC-ITEM-03 | platform-dev / Lead |

---

## 六、未验证 / 验证限制

1. **运行期 HTTP 验证全部未做**：审计时 8080（单体）/8082（云 item-service）/Redis(6380) 均未运行（仅 MySQL 3306 可用），按纪律**未启动任何服务**。因此以下均属静态推断，需在 QA/联调环境复验：SEC-ITEM-01 状态机链、SEC-ITEM-02 非在售详情枚举、SEC-ITEM-04 外部图片 URL 加载与后台 SSRF、SEC-ITEM-12 XSS 载荷、SEC-ITEM-08 限流/慢查询。
2. **只读数据库佐证范围**：仅查询了 `ershou.item` 的状态分布（2=16、4=1）、存量联系方式关键词（0 行）、站外图片 URL（0 行）、参数范围（stock 0–5/price 5–1250/negotiable=1/isUnique 0/1）、view/fav 最大值（8/7）。**未查** `sys_user`/`favorite`/订单表，也未验证“多账号刷量”实际可行性。
3. **XSS 动态验证未做**：需发布含 `<img onerror>`/`<script>` 的测试商品并走搜索命中高亮，验证 ES encoder 与前端渲染（含管理端预览）；本轮无测试账号、无运行服务。
4. **云版运行期验证未做**：SEC-ITEM-03 的“无 token 可调 lock”依据是代码（无鉴权）+ 第 5 轮联调时的旁证（当时无 Authorization 头调用成功）；本轮未重放写操作。
5. **EXIF/图片元数据（SEC-ITEM-20）未实测**：未上传真实图片提取 GPS/设备字段，仅静态推断 Thumbnailator 重编码会剥离。
6. **限流/压力测试未做**：遵守“不 DoS”纪律，SEC-ITEM-08 的资源消耗影响为静态推断。
7. **Flutter 移动端**：本轮不在审计范围（任务限定 Web + 后端 + 云）；移动端商品页为原生 `Text` 渲染无 HTML 注入面，但未逐页核对（如 WebView 地图页含商品数据需另审）。
8. **平台严选/运营台商品页**（`PlatformView`、`console/PlatformConsole*`）属平台域，本轮未审；其商品数据同样受 SEC-ITEM-04 外部图片 URL 影响（渲染面更大）。

**建议下一轮（需 Lead 放行起服务 + 测试账号）**：① 用测试账号发一件标题含 XSS 载荷/描述含手机号/图片指向 `http://127.0.0.1:8848` 的商品，走“首页→搜索高亮→详情→管理后台预览”，抓网络请求与 DOM；② 对 1/6/8 状态商品做匿名详情枚举复现 SEC-ITEM-02；③ 复现 SEC-ITEM-01 三段状态链（测试库）；④ 受控验证匿名浏览计数与 limit 边界（10 次请求级，不压测）。

---

## 七、审计覆盖清单（读了哪些文件）

- **前端**：`ershou-web/src/router/index.ts`、`views/{HomeView,PublishView,PublishListView,ItemDetailView,FavoritesView,Search 相关(无独立页)},components/{ItemCard,ItemEditDialog,ImageUploader}.vue`、`api/{item,file,auth}.ts`、`utils/{request,format}.ts`
- **单体后端**：`module/item/{controller,service,mapper,dto,vo,entity}/**`、`module/search/{controller,service,dto,document,config}/**`、`module/file/**`、`module/ai/KeywordContentAuditService.java`、`module/admin/{controller/AdminManageController,service/AdminItemService,vo/AdminItemVO}.java`、`module/user/{service/UserService,vo/PublicUserVO}.java`（只读交叉）、`common/{config/SaTokenConfig,storage/**,util/RedisKeys}.java`、`module/cart/{controller/FavoriteController,service/FavoriteService}.java`（只读交叉）、`application.yml`
- **云版**：`ershou-cloud/ershou-item-service/**`（controller/service/vo/config/application.yml/pom.xml）、`ershou-cloud/nacos-configs/ershou-item-service.yaml`、`ershou-cloud-gateway/{config/PublicPaths,filter/AuthGlobalFilter}.java`（只读交叉）
- **数据**：`ershou.item` 只读 SELECT（状态分布/关键词/URL/参数范围/计数分布）
