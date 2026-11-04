# T34 安全审计 · 交易闭环页面（购物车 / 面交单 / 信用分 / 收藏）

| 项 | 值 |
| --- | --- |
| 负责人 | `order-member`（只读审计） |
| 任务 | 第 6 轮 T34：交易闭环页面的安全审计 |
| 审计对象 | Web 交易页面（购物车/面交单/收藏/信用分/平台严选交易入口）、后端 `module/order|cart|favorite` + 信用分逻辑、云版 `ershou-cloud/ershou-order-service/**`（Feign/Seata/MQ）+ item-service 内部接口 |
| 方法 | **纯静态代码审计**；本次未启动任何服务（审计时 8080/8081/8082/8083/8088 全部未监听），因此**未执行任何 PoC**，也未下单/改状态/写库 |
| 时间 | 2026-10-21 |
| 状态 | 已完成 |
| 结论 | 共 **20 条**：P1 × 5、P2 × 12、P3 × 3。最危险的是**云版服务端口绕过网关**后 `X-User-Id` 可伪造（认证绕过）+ 内部接口无鉴权可无偿扣库存；单体侧最危险的是**继承第 1 轮的「改期不续锁 → 双重售出」仍未修**与**成交并发可双倍加信用分** |

> 等级口径：P1 = 可直接利用且影响交易/库存/信用数据完整性；P2 = 需特定条件或影响可审计性/滥用面；P3 = 健壮性/一致性/低危信息问题。
> 行号基于审计当日工作区代码（`E:\ershou`）；凡标注「静态确认」表示依据代码逻辑成立，未做运行时验证（见第 ⑤ 节）。

---

## ① 页面清单（页面 → 路由 → 接口 → 鉴权）

| # | 页面 | 路由（Web） | 前端守卫 | 主要后端接口 | 后端鉴权结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 购物车 | `/cart`（`views/CartView.vue`） | `requiresAuth`（`router/index.ts:81-86`） | `GET/POST /api/v1/cart`、`PATCH/DELETE /api/v1/cart/{cartId}`、`POST /api/v1/cart/checkout`（`CartController.java:28,34,43,51,58`） | 需登录（`SaTokenConfig.java:15-28` 白名单不含 `/cart`）；`PATCH/DELETE` 服务端校验 `userId` 归属（`CartService.java:96-116`） |
| 2 | 我的面交（买/卖双 tab、接受/拒绝/取消/成交） | `/orders`（`views/OrdersView.vue`） | `requiresAuth`（`router/index.ts:87-92`） | `GET /api/v1/meetup-orders`、`/todo-count`、`GET /{id}`、`POST /{id}/accept|reject|cancel|confirm`（`OrderController.java:28-83`） | 需登录；每个动作先 `requireOrder` 校验买卖双方身份（`OrderService.java:689-698`），卖家专属动作二次校验（`:482-484,:542-544`） |
| 3 | 我的收藏 | `/favorites`（`views/FavoritesView.vue`） | `requiresAuth`（`router/index.ts:93-98`） | `GET /api/v1/favorites`、`POST/DELETE /api/v1/favorites/{itemId}`（`FavoriteController.java:22,29,36`） | 需登录；只读写本人数据（`FavoriteService.java:32-79`） |
| 4 | 商品详情（约面交/加购/收藏入口） | `/item/:id`（`views/ItemDetailView.vue`） | 无（公开浏览） | `GET /api/v1/items/{id}`（公开）、`POST /api/v1/meetup-orders`、`POST /api/v1/cart`、`POST/DELETE /api/v1/favorites/{itemId}`、`GET /api/v1/meetup-orders?role=buyer&itemId=` | 读公开（`SaTokenConfig.java:25`）；写动作需登录（白名单只放行读；动作接口内部 `StpUtil.getLoginIdAsLong()`） |
| 5 | 私聊窗「交易卡片」（接受/拒绝/已卖出/改面交地点） | `/chat`（`views/ChatView.vue`） | `requiresAuth`（`router/index.ts:76-80`） | `GET /api/v1/meetup-orders?itemId=`、`POST /{id}/accept|reject|confirm`、`PATCH /{id}/point` | 需登录；改地点仅卖家（`OrderService.java:829-831`） |
| 6 | 我的（信用分展示） | `/profile`（`views/ProfileView.vue`，`creditScore` 见 `:58`） | `requiresAuth`（`router/index.ts:100-104`） | `GET /api/v1/users/me` | 需登录 |
| 7 | 用户主页（公开信用分） | `/user/:id`（`views/UserProfileView.vue`，信用分见 `:108`） | 无（公开） | `GET /api/v1/users/{id}/profile` | **公开**（`SaTokenConfig.java:27` 白名单）→ 见 SEC-ORD-15 |
| 8 | 平台严选（平台代卖交易入口） | `/platform`（`views/PlatformView.vue`） | 无（公开浏览） | `GET /api/v1/platform/shop/items|coupons|flash-sale`、`POST /api/v1/platform/me/coupons/{id}/claim`、`/me/flash-sale/{itemId}/grab`、`/me/items/{id}/buy` | 专区公开（`SaTokenConfig.java:21`）；动作需登录（`/platform/me/**` 不在白名单） |
| 9 | 管理端面交单列表（跨用户视口，供对照） | `/admin/orders`（`views/admin/AdminOrdersView.vue`） | `requiresAuth+requiresAdmin`（`router/index.ts:141-146`） | 管理端接口（`AdminManageController` 等，每个方法 `requireAdminId()`） | 管理员校验（属 admin 审计范围，此处仅列出） |

> 移动端 `cart_page.dart` / `meetup_orders_page.dart` / `favorites_page.dart` 复用第 1/2/3 行同一批接口，鉴权结论相同。

---

## ② 漏洞表

### P1（高）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-ORD-01 | **P1** | 云版全部交易接口（直连 `:8083`/`:8082`，绕过网关） | 云 order-service `MeetupOrderController.java:39,47` 用 `UserContext.requireUserId()`，而 `UserContext` 的值来自请求头 `X-User-Id`（`common/web/UserContextInterceptor.java:14-15` → `ApiHeaderSupport.resolveUserId`）；服务未绑定内网地址（`ershou-order-service/src/main/resources/application.yml:5-6` 只有 port）；安全边界依赖网关剥离伪造头（`ershou-cloud-gateway/.../filter/AuthGlobalFilter.java:32-39`） | 任何能访问 8083 的人可自带头 `X-User-Id: <任意用户 id>`：读任意用户「我的面交」列表、以任意用户身份创建订单并扣库存；8082 同理可调 item 内部接口。**认证绕过 + IDOR** | 静态确认（本次服务未启动，未执行 PoC；历史第 5 轮联调中曾直连 8083 带 `X-User-Id` 成功调用，同一机制） | ①服务只监听内网/由网关可达（`server.address` + 防火墙/K8s NetworkPolicy）；②服务端校验**网关注入的内部签名**（HMAC：`X-Gateway-Sign`+时间戳），无签名一律 401；③禁止外部直连端口 |
| SEC-ORD-02 | **P1** | 云版内部接口 `PUT /inner/items/{id}/lock?qty=` | `ershou-cloud/ershou-item-service/.../controller/InnerItemController.java:47-54`（无任何鉴权、无调用方身份）；`service/ItemLockService.java:35-48`（条件扣库存，无 orderNo/买家绑定、无回补机制） | 任何能访问 8082 的人可循环调用把任意在售商品库存扣到 0/`status=4`（假售罄），无订单产生、无自动回补 → **无账号即可实施的 DoS/数据破坏**；与 SEC-ORD-01 叠加成组合攻击 | 静态确认 | ①内部接口加服务间鉴权（网关签名或 mTLS）+ 仅内网；②lock 必须带 `orderNo` 幂等键并落"锁定流水"，无对应订单的分支由补偿任务回滚；③按调用方限流 |
| SEC-ORD-03 | **P1** | 云版 `POST /api/v1/meetup-orders` | `ershou-cloud/ershou-order-service/.../service/MeetupOrderService.java:70-128`：整个创建流程**无「同买家+同商品进行中单」查重、无幂等键、无 Redis 锁**（对照单体有查重：`ershou-server/.../OrderService.java:243-250` 返回 40003） | 同一用户对同一商品重复/并发提交 → **每请求都成功建单并扣一次库存**（直到库存耗尽）；可被脚本重放瞬间清空库存、生成大量垃圾订单、重复 MQ 事件；面交单语义被破坏 | 静态确认 | ①DB 唯一约束/条件插入：`(buyer_id,item_id)` 仅当进行中状态唯一（生成列或 `INSERT ... WHERE NOT EXISTS`）；②接口幂等键（`Idempotency-Key` 头 + Redis `ershou:order:idem:*`，设计 2.2 已预留 db3 前缀）；③补充活跃单查重（对齐单体 40003） |
| SEC-ORD-04 | **P1** | 单体 `POST /api/v1/meetup-orders/{id}/confirm`（卖家成交） | `ershou-server/.../order/service/OrderService.java:665-677`：`orderMapper.updateById` 无状态条件（无 `WHERE status=2`、无行锁）、`itemMapper.markSold`（`:671`）**返回值未校验**、`userMapper.addCredit(买方,2)`+`addCredit(卖方,2)`（`:676-677`）在无条件更新之后执行；信用分 SQL 为 `credit_score = GREATEST(credit_score + delta,0)`（`SysUserMapper.java:12-18`） | ①并发双击"/已卖出"或重放：两个事务都读到 `status=2` → 都完成并各加一次分 → **双方各 +4 分**（刷分）；②`markSold` 影响 0 行（商品已被置 4/5/8）时订单仍被置完成并加分 → **"已完成但商品仍可售"的状态机绕过**，可二次售卖 | 静态确认（未做并发 PoC，纪律要求；逻辑上缺少条件更新与返回值校验即成立） | ①状态流转改条件更新：`UPDATE meetup_order SET status=3,seller_confirm_at=? WHERE id=? AND status=2`，影响 0 行抛 40003；②校验 `markSold` 行数，0 行抛错回滚整单；③信用分变动写 `credit_log`（见 SEC-ORD-06）便于对账 |
| SEC-ORD-05 | **P1**（继承第 1 轮 P0） | 卖家「改面交地点/时间」`PATCH /api/v1/meetup-orders/{id}/point` + 锁过期定时任务 | `ershou-server/.../order/service/OrderService.java:824-878`：`updatePoint` 只写 `point_id/point_snapshot/time_slot_*`（`:855-861`），**从不更新 `item.lock_expire_at`**；锁耗尽逻辑 `module/system/task/ScheduledTasks.java:60-88`（`:72` 释放锁、`:79-84` 把 `status=2` 的待面交单批量置 5 已失效） | 卖家接受订单后把时间改到「当前锁到期（默认接受后 +24h）之外」 → 定时任务释放商品并作废**已确认**的面交单，商品回在售可被第三人购买 → **同一商品双重售出/超卖**；买家侧无通知。第 1 轮已作为 P0 上报，本轮复核**仍未修复** | 静态确认（第 1 轮完整静态证据链；本轮复核代码未变） | `updatePoint`/改时间后**条件续锁**：`UPDATE item SET lock_expire_at=? WHERE id=? AND status=3 AND lock_user_id=?`（到期时间=新 `time_slot_end+2h`），0 行则报错；或 `accept` 时要求一并提交时间窗 |

> 说明：P1 之后全部条目在下方 P2/P3 表。

### P2（中）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-ORD-06 | P2 | 信用分：面交完成自动 +2 | 加分调用 `OrderService.java:676-677`、`:755`（平台单买方）；全仓 `credit_log` 唯一写点是运营人工调整 `AdminUserService.java:109-117`（`bizType="admin"`），成交自动加分**不写流水**（表 DDL 见 `sql/schema.sql:314-327`） | 信用分变动不可追溯：刷分、误加、双倍加分（SEC-ORD-04）都无法对账/回滚；违反"每次变动留痕"的表设计意图 | 静态确认 | 加分同事务写 `credit_log`（`bizType="order"`、`bizId=orderId`、`before/after`），运营查询口径统一 |
| SEC-ORD-07 | P2 | 购物车/面交单/收藏全部写接口 | `SaTokenConfig.java:15-28` 仅做登录校验；全仓限流只有 `RedisKeys.java:20-29`（短信/发布/私聊三类）；order/cart 模块 grep `RateLimit/limit/setIfAbsent` 无命中；云版 order-service 无 Sentinel 规则（网关限流只覆盖 `GET /items`） | 交易接口无频率限制：可高频下单/取消/收藏/结算（DB 与私聊通知压力、骚扰卖家、探测接口）；与 SEC-ORD-04 叠加可放大并发刷分窗口 | 静态确认 | Redis 计数限流：`POST /meetup-orders`（如 10 次/分/用户）、`/cart/checkout`、`POST /favorites/*`；复用 `RedisKeys` 前缀规范 |
| SEC-ORD-08 | P2 | 商品详情「约面交」创建接口 `POST /api/v1/meetup-orders`（数量参数面） | `OrderService.java:264`：`order.setQuantity(quantity == null || quantity < 1 ? 1 : quantity)`——**只有下限、无上限、不与 stock 校验**；DTO 无边界校验（`dto/CreateOrderDTO.java:10-19`，仅 `itemId@NotNull`） | 可建 quantity=999999 的面交单（价格按单价、无支付，但污染统计与展示；后续平台代理逻辑按 quantity 扣库存的语义会异常）；数量语义不一致（购物车路径有夹取：`CartService.java:84-94`） | 静态确认 | 创建时按 `stock/isUnique` 夹取（复用 `CartService.clampQuantity`）+ DTO 加 `@Min(1)/@Max` |
| SEC-ORD-09 | P2 | 购物车「一键合并面交」`POST /api/v1/cart/checkout` | `OrderService.java:96-153`：读取 cartIds→逐单建单→`removeByIds`，**无幂等键/无分布式锁/无数据库唯一约束兜底**；活跃单查重 `:243-250` 是"先查后插"，REPEATABLE READ 下并发事务互相不可见 | 重复点击/并发重放可生成重复订单（同一购物车多次结算）；与 MQ 叠加产生重复事件 | 静态确认 | 幂等键 + 条件删除（`DELETE ... WHERE id IN (...) ` 影响行数校验）；或建单前对 `(buyer,item)` 加 Redis 锁 |
| SEC-ORD-10 | P2 | 云版内部接口 `GET /inner/orders/{id}` | `ershou-cloud/ershou-order-service/.../controller/InnerOrderController.java:24-27` 无鉴权；`service/MeetupOrderService.java:143-150` `detail(orderId)` **无归属校验**（任意 id 直接查） | 可遍历订单 id 读取任意订单：buyerId/sellerId、商品标题封面、成交价、状态（信息泄露；与 SEC-ORD-01 叠加可无账号调用） | 静态确认 | 删除调试接口或仅在 `@Profile("demo")`+内网提供，并加归属/内部签名校验 |
| SEC-ORD-11 | P2 | 云版 `POST /api/v1/meetup-orders` 的 `debugFail` 开关 | `ershou-cloud/ershou-order-service/.../dto/CreateOrderDTO.java:24`（公开 DTO 字段）+ `service/MeetupOrderService.java:115-118`（主动抛异常触发 Seata 全局回滚） | 任意登录用户可通过 body `{debugFail:true}` 强制每次下单失败并触发全局事务回滚：日志污染、Seata TC 资源消耗、可被脚本用作压测/探测；演示开关不应出现在公开生产 API | 静态确认（第 5 轮联调实测过该分支，属演示设计） | 用 `@Profile("demo")`/配置项（`ershou.order.debug-fail-enabled=false`）门控，公开 DTO 移除该字段或仅内网调试接口接受 |
| SEC-ORD-12 | P2 | 卖家改期 `PATCH /meetup-orders/{id}/point` | `OrderService.java:861` `reschedule_count+1` 但**无次数上限/无冷却/无状态变更限制**（仅 1/2 状态可改，`:832-833`） | 卖家可反复改期骚扰买家；配合 SEC-ORD-05 可反复把订单"改到锁过期之后"制造自动失效 | 静态确认 | 限制改期次数（如 ≤3）与最小间隔；改期必须续锁（SEC-ORD-05） |
| SEC-ORD-13 | P2 | 收藏 `POST /api/v1/favorites/{itemId}` | `FavoriteService.java:32-46`：直接 `insert` + `addFavCount`，**不校验商品存在/状态/是否可见**（仅唯一索引兜重，`:42-44`） | 可对任意 itemId（不存在的、已删除、被封禁的、他人的）批量写收藏行：DB 污染、按 id 刷数据；不产生计数错误但无审计价值 | 静态确认 | insert 前 `selectById` 校验存在与可收藏状态；非法 id 返回 30001 |
| SEC-ORD-14 | P2 | 面交单状态流转并发（取消 vs 成交 vs 下架作废） | `OrderService.java:568-590`（cancel：读到 1/2 后无条件 `updateById`）、`:638-685`（confirm 同）、`closeActiveOrders:604-625`（批量改 5）；均无 `WHERE status=旧值` 条件更新 | 并发「取消 + 卖家成交」可能：订单被置完成但商品已被 release 回在售，或订单被作废但商品已 markSold → 状态机与库存不一致、信用分误加/误不加 | 静态确认（未并发实测） | 全部状态流转改条件更新并校验影响行数；跨表操作（订单状态/商品状态/信用分）保持同一事务 |
| SEC-ORD-15 | P2 | 信用分公开可枚举：`GET /api/v1/users/{id}/profile`（公开）、商品详情 seller 信用分 | `PublicUserVO.java:17-18`（`creditScore/creditLevel`）+ `SaTokenConfig.java:27` 公开白名单；商品详情公开返回 `ItemVO.seller`（`ItemVO.java:50`）→ `SellerVO.java:10` 含 `creditScore` | 未登录即可按用户 id/商品 id 批量爬取任意用户信用分与等级（用户画像/社会评价歧视风险；接口可被遍历） | 静态确认 | 产品层面决定：公开页只展示等级或模糊区间；`creditScore` 仅本人可见；公开接口加基本限流 |
| SEC-ORD-16 | P2 | MQ 生产 `order.created`（云版） | `ershou-cloud/ershou-order-service/.../service/OrderEventPublisher.java:35-52`：`@TransactionalEventListener(AFTER_COMMIT)` + `catch (Exception) 只记日志` | MQ 不在 Seata 全局事务内：订单已提交但消息发送失败/消费者失败时 `item.sale_count` 少计（最终不一致，无补偿）；重复投递靠消费方幂等兜底 | 静态确认（第 5 轮联调已验证正常路径；故障路径未注入测试） | 本地消息表/Outbox（同事务落库）+ 定时重发；或定时对账 `sale_count` 与订单聚合 |
| SEC-ORD-17 | P2 | 云版 Seata AT 中间态（隔离性限制） | `ershou-cloud/ershou-item-service/.../service/ItemLockService.java:35-48`：AT 分支的本地事务在"全局回滚"前已提交，其他读事务能看到回滚前的库存/状态；`MeetupOrderService.java:115-118` 的 debugFail 正会进入该窗口 | 全局回滚窗口内并发读/下单可能看到"已扣库存"（假性库存不足），或基于中间态做决策；属 AT 模式已知读未提交问题 | 静态确认（Seata AT 机制文档级事实 + 代码路径确认） | 对关键校验用 `@GlobalLock + SELECT ... FOR UPDATE`；或改用 TCC/SAGA；演示环境可接受 |

### P3（低）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-ORD-18 | P3 | 购物车/面交单/收藏写接口参数健壮性 | `CartController.java:35-37,44-45`：手工 `Map` 解析（`Long.valueOf(String.valueOf(body.get(...)))`），缺字段/非数字抛 `NumberFormatException` → 500；`OrderService` 的 `remark`/`OrderActionDTO.reason` 无 `@Size`，超长会撞 `varchar(255)` 报 500 | 异常请求返回 500（错误码/日志噪音、前端体验差）；超长文本还会进入私聊通知（通知轰炸内容面）；不属于数据泄漏 | 静态确认 | 改用 DTO + `@Valid` + `@Size(max=255)`；统一 `MethodArgumentNotValidException` 处理 |
| SEC-ORD-19 | P3 | 下单私聊通知骚扰面 | `OrderService.java:143,206` 建单后给卖家发私聊（`notifySeller`→`notifyPeer:393-410`），无频率限制（SEC-ORD-07） | 攻击者可对不同商品反复下单骚扰卖家（40003 只限制"同商品单个买家一张进行中单"，换商品可绕过） | 静态确认 | 配合 SEC-ORD-07 限流；同卖家单位时间通知合并/上限 |
| SEC-ORD-20 | P3 | 文档/注释与实际鉴权不一致（易误导后续开发） | `OrderController.java:76` 与 `ershou-web/src/api/order.ts`（"改面交地点买卖双方都能改"）vs 实际仅卖家（`OrderService.java:829-831`）；`UpdatePointDTO` 注释同类 | 后续开发/测试按注释误判权限（把入口开给买家）时将直接越权；非当前漏洞 | 静态确认 | 统一注释与接口文档：改地点/时间仅卖家 |

---

## ③ Top 3（优先修复）

1. **云版"绕过网关"认证/内部接口组合风险（SEC-ORD-01 + SEC-ORD-02）**
   `X-User-Id` 由网关剥离并注入是唯一防线，但服务端口对局域网可直接访问（`application.yml` 仅配 port；`MeetupOrderController.java:39,47`、`InnerItemController.java:47-54`）。组合后 = **无需账号即可伪造任意用户下单 + 无需账号即可扣光任意商品库存**。
   修复顺序：网络边界（服务只监听内网/只允许网关）→ 内部签名头校验 → 内部 lock 接口幂等+订单绑定。

2. **「改期不续锁」导致已确认订单被自动作废、商品可二次售出（SEC-ORD-05，继承第 1 轮 P0 仍未修）**
   `OrderService.java:824-878` 不更新 `item.lock_expire_at`，`ScheduledTasks.java:60-88` 到期释放并作废 status=2 的订单。卖家只需把时间改到 24h 之外（正常操作即可触发）就产生**双重售出**。修复：改期条件续锁（一行 SQL 即可）。

3. **卖家成交并发/重放导致双倍信用分与状态机绕过（SEC-ORD-04）**
   `OrderService.java:665-677` 无「`WHERE status=2`」条件更新、`markSold` 返回值不校验、两次 `addCredit` 无条件执行。双击即可使双方各 +4 分；商品状态异常时"订单完成但商品仍可售"。修复：条件更新 + 行数校验 + 加分写流水（SEC-ORD-06）。

> 紧随其后：SEC-ORD-03（云版下单无幂等/无去重，库存可被脚本快速耗尽）、SEC-ORD-07（交易接口无频率限制）建议同批排期。

---

## ④ 已核实的安全控制（正面结论，给 Lead 参考）

1. **单体交易接口默认需登录**：`SaTokenConfig.java:15-28` 对 `/api/**` 统一 `StpUtil.checkLogin()`，白名单仅 auth/地图/商品读/平台 shop/用户公开主页；`/meetup-orders`、`/cart`、`/favorites` 均不在白名单。
2. **IDOR 防护（单体）**：`requireOrder` 校验买卖双方（`OrderService.java:689-698`），`GET /{id}`、accept/reject/cancel/confirm/updatePoint 全部先经过它；购物车 `update/delete` 校验 `userId`（`CartService.java:96-116`）；`checkout` 只取本人 cartId（`CartService.java:168-174`）。
3. **卖家专属动作服务端强校验**：accept（`:482-484`）、reject（`:542-544`）、updatePoint（`:829-831`）；买家 confirm 普通单只写 `buyer_confirm_at` 不推进状态（`:645-660`）。
4. **价格不由前端决定**：个人单 `dealPrice=item.getPrice()`（`:265`）；平台直购取服务端商品价（`PlatformItemService.java:329-330`）；秒杀取 DB 秒杀价（`PlatformFlashSaleService.java:173`）；前端创建参数无价格字段（`ershou-web/src/api/order.ts` 的 `CreateMeetupOrderParams`）。
5. **条件更新防超卖**：个人锁库 `ItemMapper.lockStock`（`ItemMapper.java:17-19`，`WHERE status=2`）、平台扣库存 `PlatformTradeMapper.decrementPlatformStock`（`PlatformTradeMapper.java:59-62`，`WHERE seller_type=2 AND status=2 AND stock>=qty`）、云 item 锁库存单条条件 UPDATE（`ItemLockService.java:36-38`）；平台单完成有 0 行校验（`OrderService.java:748-751`）——**平台代卖路径的防超卖/完成校验比个人单路径更严**（个人路径见 SEC-ORD-04）。
6. **网关侧伪造头防御**：`AuthGlobalFilter.java:32-39` 无条件 `headers.remove("X-User-Id")` 后再写入 Sa-Token 登录态，浏览器经 8088 无法伪造身份；`SaTokenReactorConfig.java:36-59` 非公开路径返回 401。
7. **取消库存回补有状态条件**：个人 `releaseLock` 仅 `status=3` 生效（`ItemMapper.java:22-24`）；平台 `restorePlatformStock` 仅 `status IN (2,4)` 生效（`PlatformTradeMapper.java:84-86`，不会复活已下架商品）；平台完成 `settlePlatformItem` 同样限定状态（`PlatformTradeMapper.java:73-75`）。
8. **自我交易防护**：加购（`CartService.java:57-59`）、约面交（`OrderService.java:237-239`）、平台购买（`OrderService.java:329-331`）、云下单（云 `MeetupOrderService.java:88-90`）均校验 `sellerId != buyerId`。
9. **敏感信息**：单体 `OrderVO` 仅含买家/卖家昵称，无手机号/地址（`OrderVO.java:9-44`）；公开主页 `PublicUserVO` 刻意不放联系方式（注释明确）；云版 `OrderVO` 不含手机号。

---

## ⑤ 验证方式与纪律说明

- 本次为**纯静态审计**：审计时服务端口全部未监听（8080/8081/8082/8083/8088 `Get-NetTCPConnection` 全部 down），因此**未发起任何请求、未执行 PoC、未下单/改状态/写库**。
- "验证状态=静态确认"表示：结论由代码路径 + 数据库表结构 + 框架机制（Sa-Token 拦截器、MySQL 条件更新、Seata AT 机制）推断成立；**未做运行时复现**。
- 建议 QA/修复者在修复轮用只读/预发环境复现以下 3 条（需 Lead 批准）：SEC-ORD-01（直连 8083 伪造头读 mine）、SEC-ORD-02（对测试商品调用 inner lock 一次后还原）、SEC-ORD-04（并发 confirm 需测试库，非生产）。

## ⑥ 未验证 / 不确定

1. **白名单路径规范化差异**（未验证）：Sa-Token 用原始请求 URI 匹配白名单，Spring MVC 使用规范化路径；理论上存在 `/api/v1/items/../meetup-orders`、编码斜杠等绕过尝试的空间，但 Spring Boot 3 默认拒绝可疑路径，**未实测**，不计入漏洞表。
2. **并发双倍加分/取消-成交竞态**：仅代码逻辑推断，未做并发压测（纪律禁止写操作）。
3. **云版服务端口暴露范围**：取决于部署方式（本机/容器/Docker 网络），本次只确认"未配置 `server.address` 内网绑定 + 历史联调可直连"，**未做外部网络探测**。
4. **平台代卖相关并发**（秒杀重复下单、券/库存补偿）属 platform-dev 范围，本轮只从订单落库视角核对，未深审 Lua 脚本/补偿逻辑。
5. **管理员面交单管理页**（`/admin/orders`）的越权/数据范围属 admin-member 审计范围，本轮未展开。
6. **移动端页面**仅确认复用同一批接口，未审计 Flutter 端本地缓存中的订单数据落盘安全（属 app-member 范围）。

## ⑦ 建议的修复排期（给 Lead）

| 批次 | 条目 | 说明 |
| --- | --- | --- |
| 第 1 批（P1，必须） | SEC-ORD-01、02、03、04、05 | 云边界收敛 + 内部接口鉴权；成交条件更新+加分流水；云下单幂等；改期续锁（历史 P0） |
| 第 2 批（P2） | SEC-ORD-06/07/09/10/11/12/14 | 信用分流水、交易限流、结算幂等、调试接口收口、状态机条件更新 |
| 第 3 批（P2/P3） | SEC-ORD-13/15/16/17/18/19/20 | 收藏校验、信用分脱敏、MQ outbox、Seata 隔离、参数校验、注释订正 |
