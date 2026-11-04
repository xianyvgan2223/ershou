# T38 安全审计 · 平台严选 / 平台代卖 / 优惠券 / 秒杀 / 平台运营台（含云版本）

| 项 | 值 |
| --- | --- |
| 负责人 | `platform-dev`（只读审计） |
| 任务 | 第 6 轮 T38：平台严选/平台代卖/优惠券/秒杀/平台运营台的安全审计（含云版本配置） |
| 审计对象 | Web `PlatformView.vue`、`views/console/**`、`ItemDetailView.vue` 平台商品、App `platform_page.dart` + `platform_repository.dart`；后端 `module/platform/**`（4 个 Controller、6 个 Service、8 个 Mapper、2 个 Lua）；云版 `ershou-cloud/**`（Gateway/4 服务/Nacos 配置/docker-compose/迁移 SQL） |
| 方法 | **纯静态代码审计 + 只读配置/仓库检查**；审计时 8080-8088、8848、6380 全部未监听（`docker ps` 为空），故**未发任何请求、未执行 PoC、未领券/抢购/下单/改库存/刷新缓存/写库** |
| 时间 | 2026-10-21 |
| 状态 | 已完成 |
| 结论 | 共 **25 条**：P0 × 1、P1 × 7、P2 × 12、P3 × 5。最危险的是**云版 Nacos 未授权 + 明文凭据链**（可改数据源/网关注发/库存配置）；平台业务侧最危险的是**秒杀「抢购→取消」不回收 Redis 库存与抢购记录（库存永久泄漏）** 和 **平台订单无超时释放（库存可被长期占用）**，两者叠加 + 默认固定验证码可多账号批量利用 |

> 等级口径（团队第 6 轮统一口径）：P0 = 安全类可直接利用的越权/凭据泄漏/数据损坏或阻断；P1 = 可直接利用且影响库存/资金/交易数据完整性；P2 = 需特定条件或影响滥用面/可审计性；P3 = 健壮性/一致性/低危信息问题。
> 行号基于审计当日工作区代码（`E:\ershou`，HEAD `681a5e5`）。「静态确认」= 由代码路径 + 表结构 + 框架机制推断成立，**未做运行时复现**；「未验证」单列，不计入已确认漏洞计分。

---

## ① 页面清单（页面 → 路由 → 接口 → 鉴权）

| # | 页面 | 路由（Web/App） | 前端守卫 | 主要后端接口 | 后端鉴权结论 |
| --- | --- | --- | --- | --- | --- |
| 1 | 平台严选专区（概览/代卖点/商品/券/秒杀/我的券） | `/platform`（`views/PlatformView.vue`，`router/index.ts:37-41`，`meta` 无 `requiresAuth`） | 无（公开浏览）；领券/抢购前页面 `requireLogin()` 跳登录（`PlatformView.vue:217-222`） | `GET /platform/shop/overview|points|items|coupons|flash-sale`（`PlatformShopController.java:42-75`）；`POST /platform/me/coupons/{id}/claim`、`POST /platform/me/flash-sale/{itemId}/grab`（`PlatformMeController.java:42-58`） | 公开读**匿名可访问**（`SaTokenConfig.java:19-21` 白名单 `/platform/shop/**`）；`/platform/me/**` 不在白名单 → Sa-Token 拦截需登录（`SaTokenConfig.java:15-28`） |
| 2 | 商品详情（平台商品金标/官方严选入口） | `/item/:id`（`views/ItemDetailView.vue`，`router/index.ts:49-53`） | 无（公开浏览） | `GET /api/v1/items/{id}`（公开） | 读公开（`SaTokenConfig.java:25`）；平台标识来自服务端字段 `sellerType`（`ItemDetailView.vue:55-56,354-363,469-473`），**Web 端无平台直购按钮**（`api/platform.ts:335` 的 `buyPlatformItem` 仅定义，无页面调用） |
| 3 | 平台运营台 Shell + 7 个子页（概览/商品/代卖点/券/秒杀/订单/缓存） | `/console/platform` + `overview|items|points|coupons|flash-sales|orders|cache`（`router/index.ts:166-214`） | `requiresAuth + requiresAdmin`（`router/index.ts:167-168,233-246`；profile 从 `/users/me` 拉取后判断 `isAdmin`，`stores/user.ts:27`） | 17 个运营接口（`PlatformConsoleController.java:61-202`）+ `POST /platform/admin/cache/refresh`（`PlatformAdminController.java:102-121`）+ 代卖点 CRUD/发布商品 6 个（`PlatformAdminController.java:55-101`） | **24 个管理接口全部第一行 `adminAuthService.requireAdminId()`**（见 ④-1）；admin 判定为用户表 `role_flags & 8` 或 `ershou.admin.user-ids` 白名单（`AdminAuthService.java:22-51`） |
| 4 | 旧后台入口跳转 | `/admin/platform-points` → redirect `/console/platform/points`（`router/index.ts:158-159`） | 继承 console 守卫 | 同上 | 同上 |
| 5 | 首页/搜索里的平台商品角标 | `/`（`HomeView.vue` + `components/ItemCard.vue`） | 无 | `GET /api/v1/items`、`/api/v1/search/**`（公开读） | 公开；`sellerType` 由服务端 `ItemVO` 返回（`ItemService.java:744`），非请求参数可控 |
| 6 | App 平台严选页（专区/券/秒杀） | `ui/pages/platform_page.dart`（首页入口 `home_page.dart:184`） | 登录态由 App 全局 auth 管理；未登录时接口返回 10002 由页面报错 | 5 个 shop 读 + `claim`/`grab`/`buy`（`platform_repository.dart:19-124`） | 与 Web 相同：shop 匿名可读，me 动作需登录（后端认定） |
| 7 | 云版平台入口（经网关） | `http://127.0.0.1:8088/api/v1/platform/**` | — | 全部反代旧单体 `/api/v1/platform/**`（`GatewayRoutesConfig.java:57-58`） | `PublicPaths.java:43-44` 放行 `GET /api/v1/platform/shop/**`；`me/**`、`admin/**` 网关要求登录（`SaTokenReactorConfig.java:36-59`），**admin 角色由单体二次校验** |
| 8 | 云版内部攻击面（非页面） | 直连 `:8081/:8082/:8083` 的 `/api/v1/**`、`/inner/**` | — | `X-User-Id` 信任头（`UserController.java:35-43`、`MeetupOrderController.java:37-47`）；`PUT /inner/items/{id}/lock`（`InnerItemController.java:46-52`）；`GET /inner/orders/{id}`（`InnerOrderController.java:22-24`） | **无网关层鉴权、内部接口无鉴权**（见 SEC-PLT-02/03） |

> 补充：运营台 7 个子页共 18 个“运营台接口”（Console 17 + cache refresh 1），加 6 个代卖点/发布接口 = **24 个平台管理接口**，全部有后端管理员校验（审计重点之一，未发现缺失鉴权）。

---

## ② 漏洞表

### P0（阻断/最高危）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-PLT-01 | **P0** | 云版配置中心（Nacos 8848）+ 仓库凭据 | `docker-compose.yml:17-24`（`NACOS_AUTH_ENABLE=false`、`8848:8848` 发布到 0.0.0.0）；4 个服务 `application.yml:10/13/19/22` 均 `optional:nacos:...&refreshEnabled=true`；`nacos-configs/ershou-gateway.yaml:12-13`、`ershou-user-service.yaml:9-10,19-20`、`ershou-item-service.yaml:9-10,19-20,25-26`、`ershou-order-service.yaml:9-10,19-20` 含 MySQL/Redis/RabbitMQ/Seata 明文口令；`sql/init-cloud-databases.sql:26,39-50` 与 `scripts/audit-cache.mjs:15-16` 硬编码同一真实密码；`docker/seata/application.yml:22-25,37-38,48` 控制台/注册中心口令+`secretKey` | 局域网内任何人可 ① `GET /nacos/v1/cs/configs` 拉取全部配置（拿到 MySQL/Redis/RabbitMQ/Seata 口令）；② `POST` 改配置（如 `ershou.gateway.legacy-uri` 指向恶意主机 → 网关把用户 `Authorization` 转发给攻击者；`spring.datasource.*`/Redis 指向攻击者库；平台券/秒杀库存、`ershou.item.page-max` 等业务配置被篡改）；③ 结合 `refreshEnabled`/重启让改动生效。等价基础设施与平台数据沦陷（影响库存/券/订单完整性）。 | 静态确认（未做请求验证） | Nacos 开启鉴权+强口令、只监听 `127.0.0.1`/内网、不发布 8848；配置密码改 `${MYSQL_PASSWORD}` 等环境变量注入；仓库清除真实口令并轮换（含历史 commit）；生产按安全组白名单。 |
| | | | | | | |

### P1（高）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-PLT-02 | **P1** | 云版服务绕过网关伪造身份（跨范围，T34 SEC-ORD-01 同源） | 服务只配了 `server.port`（`ershou-user-service/application.yml:11`、`item:2`、`order:6`），未绑定 `server.address`；`UserController.java:35-43` 优先取 `X-User-Id`；`MeetupOrderController.java:37-47` 用 `UserContext.requireUserId()`；`UserContextInterceptor.java:13` ← `ApiHeaderSupport.resolveUserId` 直接读头；防伪造只在网关（`AuthGlobalFilter.java:30-40`） | 能访问 8081/8083 的任何人不带 token 自带头 `X-User-Id: <任意用户id>` 即可以该用户身份读 `/users/me`、创建/查看面交单（云商品库存可被代扣）；平台商品在云 item 库有副本（见 SEC-PLT-20），云边界失守会间接破坏平台库存展示与订单数据。 | 静态确认（跨引 T34 SEC-ORD-01；运行时未复现） | 服务只监听内网/仅允许网关来源 + 内部签名头（网关私钥签名，服务验签）+ mTLS；不要把 `X-User-Id` 当作唯一身份来源。 |
| SEC-PLT-03 | **P1** | 云版内部库存接口无鉴权（跨范围，T34 SEC-ORD-02 同源） | `InnerItemController.java:36-52`（`GET /inner/items/{id}`、`PUT /inner/items/{id}/lock?qty=` 无任何鉴权）；`ItemLockService.java:35-49` 直接条件扣库存；`ershou_item.item` 含平台商品副本（`init-cloud-databases.sql:129-130`，迁移按公共列全量复制 `migrate-data.ps1:128-145`）；`InnerOrderController.java:22-24` 暴露订单详情 | 任何人直连 8082 可 `PUT /inner/items/{id}/lock?qty=999999` 把商品（含平台商品副本）库存扣光/置售出（拒绝服务/数据损坏），可重复调用；`/inner/orders/{id}` 可枚举订单。审计纪律禁止写操作，故未实测。 | 静态确认（未发请求） | 内部接口仅允许内网/网关或 Feign 调用（网络 ACL + 内部 token/签名）；lock 接口要求订单号幂等键并与订单绑定；生产不发布 8081-8083。 |
| SEC-PLT-04 | **P1** | 秒杀抢购后取消订单（库存/记录不回收） | 抢购流程：Lua 扣 `seckill:stock` + `seckill:users`（`PlatformFlashSaleService.java:158-168`）→ 建平台单（`:170-184`，价取 DB 秒杀价 `:173`）→ 落 `platform_seckill_record`（`:188-204`）；取消：`OrderService.java:567-590` → `releaseItemLock`（`:712-719`）只对**平台单还 item.stock**（`PlatformTradeMapper.restorePlatformStock:84-86`），**无任何 Redis 秒杀库存/已购 Hash 补偿**；`PlatformSeckillRecord` 全仓库只有 insert/select，取消不清理（grep 无 delete）；`PlatformCacheService` 只有抢购补偿 `rollbackSeckill`（失败建单时用，`:152-164`） | 抢到秒杀后取消：DB `item.stock` 恢复、`platform_seckill_record` 保留、Redis `seckill:stock:{itemId}` 永久 -1、`seckill:users` 仍记该用户。多账号（默认固定验证码，见 SEC-PLT-06）可循环“抢购→取消”把 Redis 秒杀库存打到 0 → 正常用户全部提示“抢完”，DB 库存却还在；只能管理员“强制重建缓存”修复。库存数据不一致 + 活动可用性被破坏。 | 静态确认（代码路径完整；未实测写操作） | 取消/退款/超时关单时按订单来源做幂等补偿：反向 Lua（INCR stock + HINCRBY -1），并删除/标记 `platform_seckill_record`；或定时任务以 DB 记录为真值重建（需处理“已取消”记录），并增加 Redis/DB 对账告警。 |
| SEC-PLT-05 | **P1** | 平台订单无超时自动关闭（库存长期占用 DoS） | `platformBuy`（`OrderService.java:308-382`）只写 `status=2`（`:359`）与 `sellerConfirmAt`（`:361`），**无支付/取货超时字段**，也不锁库；定时任务：`ScheduledTasks.java:52-81` 只处理 `item.status=3` 的锁过期（平台 v1.1 不写 status=3），`:87-101` 只处理**意向单 status=1** → **平台单 status=2 不被任何定时任务清理**；取消需买家主动（`OrderService.java:567-590`）。 | 买家抢到秒杀/直购后不确认也不取消，库存被永占（`platformBuy` 已扣 item.stock，秒杀 Redis 也已被扣，见 SEC-PLT-04）；配合多账号可把平台商品库存全部“拍下不取”，前台显示售罄（DoS），运营台只能人工核对。 | 静态确认 | 平台单增加超时（如 72h 自提超时）定时关单，复用取消路径做库存/秒杀 Redis 补偿；对“未完成平台单数/总量”做用户级上限；运营台订单页加“超时待处理”标识。 |
| SEC-PLT-06 | **P1** | 固定短信验证码 + 无注册/活动风控 → 多账号绕过每人限领/限购 | `application.yml:102-104`（`sms.mock: true`、`mock-code: "123456"`，默认开启）；`UserService.java:58-77`（mock 时固定码、日志打印、不真发短信）；云 user-service 同配置 `nacos-configs/ershou-user-service.yaml:49-51`；领券/抢购的限流/限领都只按 userId：`coupon_claim.lua:13-26`（HGET userId）、`seckill_grab.lua:14-27`；5 秒限流 key 也是 per-user（`PlatformCacheKeys.java:54-62`） | 任何人可用同一手机号验证码 123456 批量注册账号（无图形/设备/IP 风控），然后用 N 个账号绕过“每人限 1 张/1 件”，批量领光券、抢光秒杀、并（结合 SEC-PLT-04/05）把平台商品库存打空；短信“每日上限”是 per-phone 不是 per-IP，挡不住批量小号。 | 静态确认 | 演示环境也关闭 mock 或仅白名单手机号可用；注册加图形验证码/IP+设备维度日限；领券/抢购加账户年龄、信用分、设备指纹、风控名单；活动接口网关级全局限流。 |
| SEC-PLT-07 | **P1** | App 平台页凭证安全（跨范围，T37-app 同源） | token 明文存 SharedPreferences（`local_store.dart:12-25`）；服务器地址可任意配置（`server_config.dart:26-39`）；明文 HTTP（`api_client.dart:65-73`，`baseUrl: config.apiBase`）；请求头注入 token（`:74-82`）；平台页领券/抢购复用该客户端（`platform_page.dart:234-271`） | 用户可被诱导改服务器地址/遭遇中间人 → Bearer token 外发，攻击者获得完整账号权限（代领券、代抢购、查看订单/资料；token 与 Web/单体共享，影响全站）。 | 静态确认（跨引 T37-app） | App 强制 HTTPS/证书校验、服务器地址白名单+域名校验、token 存 Keystore/Keychain、敏感动作二次确认与设备绑定。 |
| SEC-PLT-08 | **P1** | 明文凭据入库 + 中间件默认口令（独立于 Nacos 暴露面） | 仓库真实口令：`nacos-configs/*.yaml`、`sql/init-cloud-databases.sql:26,39-50`、`scripts/audit-cache.mjs:15-16`；RabbitMQ `guest/guest` + 15672 管理台（`docker-compose.yml:42-46`）；Seata 控制台 `seata/seata`（`docker/seata/application.yml:22-25`，`docker-compose.yml:65-66` 发布 7091）；`docker/seata/application.yml:48` `secretKey` 明文；`.gitignore` 只忽略 `.env`，上述文件均已提交（`git ls-files` 确认） | 拿到仓库/构建产物/备份的任何人可直接连 MySQL/Redis/RabbitMQ 管理台/Seata 控制台；RabbitMQ 可发布伪造 `order.created` 事件影响 item 销量/统计（`OrderCreatedListener` 消费该队列），Seata 控制台可干预事务。 | 静态确认 | 全部凭据改 `.env`/密钥管理注入并轮换；Rabbit 禁用 guest、管理台仅 127.0.0.1；Seata 控制台强口令/关闭；仓库历史清理。 |
| | | | | | | |

### P2（中）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-PLT-09 | P2 | Redis 数据隔离（平台库存/券缓存与 Sa-Token 同库） | 单体 Redis 默认 db0（`application-dev.yml:12-16`，`database: ${REDIS_DATABASE:0}`）；平台缓存/库存 key 全在 `ershou:platform:*`（`PlatformCacheKeys.java:16-67`，含 `coupon:stock`、`seckill:stock`、`coupon:users`）；云 user-service 的 Sa-Token 会话固定 db0（`nacos-configs/ershou-user-service.yaml:45-46`） | 设计口径“db0 只放登录态、业务库独立”未落实：任何能读写 db0 的组件（含被 SEC-PLT-02 攻陷的云服务/任何拿到密码的人）可直接改 `seckill:stock:*`/`coupon:stock:*` 造成超发或假售罄；与第 5 轮“Redis 隔离”验收点不符。 | 静态确认 | 平台库存/缓存迁独立 db（如 db4）并改 `PlatformCacheKeys` 前缀+配置；Sa-Token 保持 db0；补 Redis ACL/监控（库存 key 变更告警）。 |
| SEC-PLT-10 | P2 | 运营台缓存强制重建接口（无频控 + 重建竞态） | `PlatformAdminController.java:102-121`（`POST /cache/refresh`，admin 校验后 `knownCampusIds()`+`refresh()`，无频率限制）；`PlatformCacheService.java:279-285`（先删 4 类列表 key 再 `warmup(true)`）；`warmup(true)`（`:250-268`）先删 stock/users key 再按 DB 计数重建（`:322-348, :404-460`）；`refresh()` 期间领券/抢购 Lua 见 key 不存在返回 -1（`coupon_claim.lua:13-16`） | ① 管理员误点/脚本刷 → 全量回收站重建，活动窗口内领券/抢购全部返回“未预热”，可用性自伤；② 重建“先删后按 DB 计数”与并发 claim/grab 有竞态窗口：极端情况下券的 Redis 剩余库存被重算为偏高值（DB 无“已发总数≤total_stock”约束，仅靠唯一键和 Lua）→ 可能超出券实际总库存发放；秒杀有 DB 条件更新兜底不会超卖但会对账失衡。 | 静态确认（竞态窗口未压测复现） | 重建用“新 key + 原子切换”；加接口频控/二次确认；DB 增加发放计数或对账任务；活动进行中禁止全量重建。 |
| SEC-PLT-11 | P2 | 秒杀同商品多场次共享 per-item Redis key | Redis key 只按 itemId（`PlatformCacheKeys.java:45-52`）；`platform_flash_sale_item` 唯一键只约束 `(flash_sale_id,item_id)`（`sql/migration-platform.sql:138`），同一 item 可加入多个进行中场次；`rebuildFlashSale`/`warmupSeckills` 每个场次都会重写同一 key（`PlatformCacheService.java:351-380, :429-460`）；`grab` 用 `selectCurrentByItemId` 取一个场次（`PlatformFlashSaleService.java:136`）但库存/已购 key 共用 | 两场同时进行时 Redis 库存/已购 Hash 互相覆盖：可能出现误判售罄、某场多卖（item.stock 由 DB 条件更新兜底不超卖，但按场次 seckill_stock 语义可错乱）、sold_count/剩余展示错乱；预热顺序决定最后值，运营无法对账。 | 静态确认 | key 加 sceneId（`seckill:stock:{sceneId}:{itemId}`、`seckill:users:{sceneId}:{itemId}`），限购记录按场次维度；业务上禁止同商品加入多个进行中场次（服务端校验）。 |
| SEC-PLT-12 | P2 | 运营台手动下架/库存规零不联动进行中平台单 | `PlatformItemService.java:168-182`（`adminOffline` 只 `itemMapper.offlineItem` 置 status=5、清缓存，**未调用 `orderService.closeActiveOrders`**）；`adminUpdate`（`:131-165`）可把 stock 改 0 也不处理挂单；对比过期任务会 `closeActiveOrders`（`ScheduledTasks.java:112-145`） | 管理员下架后，已存在的平台单（status=2）永远“待面交”：买家点“我已收到”时 `settlePlatformItem` 的 `WHERE status IN (2,4)`（`PlatformTradeMapper.java:73-75`）匹配 0 行 → 报错/卡单；库存/秒杀 Redis 也不会回补（叠加 SEC-PLT-04）。 | 静态确认 | 下架/库存规零/重新上架统一联动：关闭进行中平台单 + 库存/秒杀补偿；运营台提示影响单数。 |
| SEC-PLT-13 | P2 | 限流覆盖不足 + Redis 故障 fail-open | 仅领券/抢购有 per-user 5s 限流（`PlatformCacheService.java:102-113`、`PlatformCacheKeys.java:54-62`）；Redis 异常时 **return true 放行**（`:109-112`）；`POST /platform/me/items/{id}/buy` 无任何限流（`PlatformMeController.java:60-65`）；运营台接口无频率限制（`PlatformConsoleController.java:61-202`） | 抢购/领券脚本可绕过（只用不同账号，SEC-PLT-06 提供小号）；直购可高频建单/取消制造垃圾订单；Redis 抖动时限流失效；运营台接口被刷 → DB 压力/缓存重建 DoS。 | 静态确认 | 限流统一到网关或服务切面（用户+IP+接口维度，滑动窗口）；Redis 故障时 fail-closed 或降级到本地限流；运营写接口加操作频率+幂等。 |
| SEC-PLT-14 | P2 | 公开接口暴露实时运营情报 + 无爬虫防护 | `PlatformShopController.java:42-75` 匿名返回券实时剩余（`PlatformCouponService.java:83-93` 逐券读 Redis）、秒杀剩余/销量、平台商品分页；`PublicPaths.java:43-44` 放行 + 单体白名单（`SaTokenConfig.java:19-21`）；均无限流/验证码 | 竞品/脚本可轮询抓取券库存、秒杀剩余量与时间窗，为扫货/压价/自动化抢购做侦察；也可被用来判断“何时补库存”。 | 静态确认 | 公开接口库存做模糊化（充足/紧张/售罄）、加网关限流与 UA/Referer 校验；活动开始时间不精确到秒外泄。 |
| SEC-PLT-15 | P2 | 网关公开路径判定前缀过宽/口径不一致（未验证） | `PublicPaths.java:34`（`/api/v1/schools` 前缀）、`:40`（`/api/v1/map` 前缀，匹配 `/api/v1/mapxxx`）、`:43`（`/api/v1/platform/shop/` 带尾斜杠 → `/api/v1/platform/shop` 反被 401）；鉴权用 `exchange.getRequest().getPath().value()`（`SaTokenReactorConfig.java:41`），路由用 PathPattern（`GatewayRoutesConfig.java:39-58`） | 前缀过宽会把未来新增 `/api/v1/map-*` 等接口误当公开；鉴权与路由若对编码斜杠/`..`/分号参数的规范化不一致，理论存在“判定公开但路由到受保护服务”的绕过（Spring 默认拒绝可疑路径，未实测，不计入已确认漏洞）；尾斜杠是功能问题（`/platform/shop` 401）。 | 未验证（静态发现） | 用规范化 `PathContainer` 与路由共用 matcher；前缀改成 `/api/v1/map/`、`/api/v1/schools/`；补安全单测（大小写/编码/尾斜杠/矩阵参数）。 |
| SEC-PLT-16 | P2 | 运营台管理操作无审计日志 | `module/platform/**` 全目录无 `AuditLogService`/审计调用（grep 无 `audit` 命中）；`PlatformItemService.publish:247-303`、`adminUpdate:131-165`、`adminOffline:168-182`、券/秒杀 CRUD、`PlatformAdminController.java:102-121` 缓存刷新均无操作日志 | 管理员账号被盗/越权或误操作（下架、改价、重建缓存）后**无法追溯操作人/前后值**；平台“官方严选”商品被篡改时无证据链，影响事故定责与恢复。 | 静态确认 | 平台写接口统一审计切面（操作人/IP/UA/对象/前后值/结果）写 `sys_audit_log`；关键操作二次确认+可回滚。 |
| SEC-PLT-17 | P2 | 中间件端口全发布 0.0.0.0 + 默认口令/无鉴权（Nacos 之外） | `docker-compose.yml:23-24`（8848）、`:44-46`（5672/15672 guest/guest）、`:64-66`（8091/7091）、`:81-82`（8858 Sentinel 无鉴权，`docker/sentinel/Dockerfile` 1.8.8 默认无登录）；`docker/seata/application.yml:22-25`（seata/seata） | 局域网可访问 RabbitMQ 管理台（伪造/重放 MQ 事件影响销量统计）、Sentinel 控制台（改/清网关限流规则 → 限流失效）、Seata 控制台；端口暴露面扩大 SEC-PLT-01/03 的利用条件。 | 静态确认 | 中间件端口只发布 127.0.0.1/内网；强口令；Sentinel 开鉴权；Seata 控制台关闭或改强口令+网络白名单。 |
| SEC-PLT-18 | P2 | Web 凭证存储（localStorage）、XSS 面核对 | token 存 localStorage（`utils/request.ts:35-46,70-75`）；`stores/user.ts:14-27` 用 token 判登录；平台/运营台页面**未发现 `v-html`/`innerHTML`**（grep 0 命中，插值渲染安全）；同源 `ItemCard.vue:124-125` 有 `v-html` 渲染 ES 高亮，依赖 ES `encoder: html` 转义（`ItemSearchService.java:137-141`） | token 若被同源 XSS 窃取，攻击者可代领券/抢购/进运营台（admin token 风险最大）；当前平台/运营台自身无注入点，但同源其他页面是潜在链，建议加固而非按漏洞计。 | 静态确认（XSS sink 已核对；ES 转义依赖 Search 模块，跨范围） | token 改 HttpOnly Cookie(SameSite) 或内存+短时刷新；CSP 限制内联/外链；对 `v-html` 统一 sanitize（DOMPurify）并保留 ES encoder=html 服务端转义。 |
| SEC-PLT-19 | P2 | 平台商品发布/编辑无内容审核、图片外链无校验 | `PlatformItemService.publish:247-303`（仅校验代卖点/分类/图片非空，图片 URL 直接存储）；`AdminItemUpdateDTO` 仅长度/数值校验（无 URL/域名白名单）；`PlatformConsoleItems.vue:209` 前端表单直接提交图片地址 | 管理员账号被越权/被盗时可在“官方严选”位发布违规内容或第三方追踪图片（访客 IP/Referer 泄露给外域）；无敏感词审核/操作审计（叠加 SEC-PLT-16）。 | 静态确认 | 图片统一走对象存储上传+域名白名单；标题/描述过敏感词审核；发布/编辑纳入审计与双人复核。 |
| SEC-PLT-20 | P2 | 云版平台商品数据副本漂移（一致性） | 迁移脚本把 `item`（含 `seller_type`/`platform_point_id`，`sql/init-cloud-databases.sql:126-172`，按公共列全量复制 `sql/migrate-data.ps1:128-145`）复制到 `ershou_item`；云 item-service 返回 `sellerType/platformPointId`（`ItemQueryService.java:180-181`）但**不填 `platformPointName`**（VO 字段存在但 toVO 未赋值）；平台下单/扣库存仍在单体（`OrderService.platformBuy`） | 经网关 `GET /api/v1/items/**` 展示的“官方严选”库存/状态是迁移时快照，平台真实售出/下架后不会同步 → 用户看到在售但实际买不到（误导成交）；详情页平台自提点名称为空。非权限漏洞，但属云版平台功能的安全/一致性风险。 | 静态确认 | 平台商品在云版改为只读镜像+定时同步，或网关把平台商品路由回单体；云详情补 `platformPointName`（Feign/冗余表）。 |

### P3（低）

| ID | 等级 | 页面/接口 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| SEC-PLT-21 | P3 | `per_user_limit` 配置与 DB 唯一键不一致 | 券表 `per_user_limit`（`sql/migration-platform.sql:83`）但唯一键 `uk_pcoupon_user(coupon_id,user_id)`（`:105`）；秒杀 `:133` 与 `uk_pseckill_item_user(item_id,user_id)`（`:151`）；Lua 按 `ARGV[2]` 允许 >1（`coupon_claim.lua:20-23`），DB 第二次必 DuplicateKey → 回滚并返回 -2（`PlatformCouponService.java:127-142`） | 运营若把每人限领/限购配成 >1，Redis 放行、DB 拒绝，用户看到“超限”且库存被短暂回滚；不是可被攻击者利用的越权，但会造成运营配置误解与日志噪声。 | 静态确认 | 要么支持多张（唯一键改 `(coupon_id,user_id,seq)` + 领取数量列），要么在保存 DTO/服务层把 per-user-limit 夹取为 1 并文档化。 |
| SEC-PLT-22 | P3 | 网关 CORS 全放行 | `CorsConfig.java:23-28`（`allowedOriginPatterns("*")`、所有方法/头；`allowCredentials=false`） | 无 Cookie 且 token 需显式 `Authorization` 头，CSRF 风险低；但恶意站点可跨域探测公开接口；若 token 被 XSS 窃取，CORS 全放行便于跨域外带调用。 | 静态确认 | 生产收敛到具体域名（`https://ershou.example.edu.cn`），只放行必要方法/头。 |
| SEC-PLT-23 | P3 | 运营台缓存统计接口信息面 | `PlatformConsoleService.java:161-201` 返回 SCAN 到的全部 `ershou:platform:*` key、TTL、String 值原文（含库存数值、缓存 JSON）；admin 校验后无频控 | 管理员 token 被盗/越权时可一次性拿到全部活动库存/缓存快照；正常运营使用无害。 | 静态确认 | 返回摘要（key 数/类型/过期），敏感值脱敏；加频控。 |
| SEC-PLT-24 | P3 | 平台官方卖家 id 可配置 | `PlatformProperties.java:18-24`（`ershou.platform.seller-id:1000`）；`OrderService.java:84-85` 同键；平台商品发布用该 id（`PlatformItemService.java:269`） | 若部署时误配成普通用户 id，该用户会成为平台商品卖家（可被 `isPlatformOrder` 识别、结算/信用分逻辑走偏、订单展示为“平台官方”）；配置错误风险，非远程可利用。 | 静态确认 | 启动时校验 seller-id 必须指向 `role_flags=16` 的官方账号；配置文档标注禁止改。 |
| SEC-PLT-25 | P3 | 镜像以 root 运行 | `docker/Dockerfile.service:15-31` 无 `USER` 指令（基础镜像 `eclipse-temurin:17-jre`） | 容器内进程为 root，逃逸/文件写入影响面更大（演示环境风险低）。 | 静态确认 | 创建非 root 用户并 `USER appuser`；只读根文件系统。 |
| SEC-PLT-26 | P3 | 平台账号 role_flags 设计约束（已核实不越权） | 官方账号 `role_flags=16`（`sql/migration-platform.sql:160-168`），管理员位=8（`application.yml:110-112`，`AdminAuthService.java:40`），`16 & 8 == 0`；平台账号手机号占位 `PLATFORM_OFFICIAL` 不满足登录手机号正则（`LoginDTO.java:10`、`SmsCodeDTO.java:10`） | 当前平台账号不可登录、无运营台权限（已核实，非漏洞）。**风险提示**：若未来把该账号 role_flags 改成 24 或加入 admin 白名单，会直接获得运营台权限，需在文档/启动校验中约束。 | 静态确认（正面结论） | 启动断言平台账号 role_flags 不含 8；文档明确“平台自营位不可赋予登录账号”。 |

> 计数：P0 × 1（SEC-PLT-01）、P1 × 7（SEC-PLT-02～08）、P2 × 12（SEC-PLT-09～20）、P3 × 5（SEC-PLT-21～25）+ 1 条正面核实（SEC-PLT-26，不计分）→ **确认漏洞 25 条**。

---

## ③ Top 3（优先修复）

1. **P0 · 云版配置中心未授权 + 明文凭据链（SEC-PLT-01）**
   Nacos `NACOS_AUTH_ENABLE=false` 且 8848 发布到 0.0.0.0，4 个服务从 Nacos 拉配置且 `refreshEnabled=true`；同时仓库/SQL/脚本里有真实 MySQL/Redis/RabbitMQ/Seata 口令。攻击者只需在局域网内即可：拉取凭据 → 改 `ershou.gateway.legacy-uri` 窃取用户 token/改数据源或库存配置 → 平台券/秒杀/订单数据被操纵。**修复**：Nacos 鉴权+只监听内网+不发布端口；密码全部环境变量化并轮换；仓库清除真实凭据（含历史 commit）。

2. **P1 · 云版绕过网关 + 内部接口无鉴权（SEC-PLT-02/03，与 T34 SEC-ORD-01/02 同源）**
   服务未绑定内网地址、唯一身份来源是 `X-User-Id` 头、`/inner/items/{id}/lock` 完全无鉴权。攻击者无需账号即可伪造任意用户读面交单/建单，并可直连 8082 把商品（含平台商品副本）库存扣光。**修复**：网络边界（服务只监听内网/只允许网关与 Feign）→ 内部签名头/服务身份认证 → lock 接口绑定订单幂等键；生产不发布 8081-8083。

3. **P1 · 秒杀“抢购→取消”库存永久泄漏 + 平台单无超时释放（SEC-PLT-04/05）**
   取消只回补 `item.stock`，不回补 Redis `seckill:stock`/`seckill:users`，也不清理 `platform_seckill_record`；平台单 `status=2` 不在任何定时任务里、无超时关单。多账号（默认固定验证码 SEC-PLT-06 放大）可循环抢购→取消把秒杀 Redis 库存打到 0（前台永远“抢完”、DB 还有货），或“拍下不取”长期占库存。**修复**：取消/退款/超时统一走幂等补偿（反向 Lua + 记录状态机 + 定时关单），并加 Redis/DB 对账告警。

> 紧随其后建议同批排期：SEC-PLT-06（固定验证码+无注册风控，是上述库存 DoS 的“弹药”）、SEC-PLT-09（Redis 未隔离）、SEC-PLT-10（缓存重建竞态）。

---

## ④ 已核实的安全控制（正面结论，给 Lead 参考）

1. **24 个平台管理接口全部有后端管理员校验**：`PlatformConsoleController.java` 17 个方法（`:61,70,82,90,98,108,115,122,130,140,147,154,162,170,178,188,200`）与 `PlatformAdminController.java` 7 个方法（`:55,62,69,76,84,94,103`）每个方法第一行都是 `adminAuthService.requireAdminId()`；`AdminAuthService.java:44-51` 先 `checkLogin` 再判 `role_flags & 8`/白名单。未发现“漏鉴权/只靠前端隐藏”的管理接口。
2. **价格/库存不由客户端决定**：平台直购价取服务端商品价（`PlatformItemService.java:329-330`）；秒杀价取 DB 场次价（`PlatformFlashSaleService.java:173,194`）；`PlatformBuyDTO`/`PlatformItemPublishDTO` 无价格字段（价格只由管理员在运营台设置）。
3. **`seller_type` 不可由请求参数伪造**：个人发布硬编码 `sellerType=1`（`ItemService.java:349`）；平台发布 DTO 无该字段（管理员接口固定 `sellerType=2`，`PlatformItemService.java:269-270`）；商品列表只有 `sellerId` 过滤（`ItemService.java:191-192`），没有 `seller_type` 入参。
4. **SQL 注入未发现**：平台 Mapper 全部 `#{}` 绑定参数、动态条件用 `<if>`（`PlatformItemMapper.java:110-146` 等 8 个 mapper），排序参数走白名单 `normalizeSort`（`PlatformItemService.java:376-381`），**无任何 `${}` 拼接**。
5. **越权编辑/下架平台商品未发现**：普通用户改/下架/重发商品都要 `item.sellerId == userId`（`ItemService.java:398-399,494-495,531-532,588-589`），平台商品 `seller_id=1000` 无法匹配；运营台接口有 admin 校验。
6. **优惠券/秒杀核心防超发机制成立（正常路径）**：Lua 原子判定库存+限领（`coupon_claim.lua:13-26`、`seckill_grab.lua:14-27`）；DB 唯一键兜底并补偿（`PlatformCouponService.java:127-142`）；秒杀 DB 条件扣库存（`PlatformTradeMapper.java:59-62`）；完成/取消有状态条件防止复活已处置商品（`:73-86`）。**问题出在取消/重建等旁路（SEC-PLT-04/10/11），正常抢购路径未发现超卖/超发。**
7. **重复领取/重复抢购被 DB 权威拦截**：`(coupon_id,user_id)`、`(item_id,user_id)` 唯一键（`sql/migration-platform.sql:105,151`）+ 领取前查重（`PlatformCouponService.java:113-116`）+ 抢购前查记录（`PlatformFlashSaleService.java:145-147`）。
8. **平台订单身份与状态机基本正确**：`requireOrder` 校验买卖双方（`OrderService.java:689-698`）；取消只允许 status 1/2（`:570-572`）；平台单完成由买家触发并校验商品状态（`:735-762`，`settlePlatformItem` 0 行会报错）；改面交点仅卖家（`:824-831`，平台账号不可登录→平台单点位不可改，属功能限制）。未发现“已完成后取消/越权取消”的绕过。
9. **网关剥离伪造头**：`AuthGlobalFilter.java:32-39` 无条件 `headers.remove("X-User-Id")` 后再写 Sa-Token 用户 id；浏览器经 8088 无法伪造身份（问题在直连内部端口，SEC-PLT-02）。
10. **前端平台/运营台页面无 XSS sink**：`PlatformView.vue`、`views/console/*.vue`、`ItemDetailView.vue` grep `v-html|innerHTML|document.write|eval` 0 命中；登录跳转固定 `/login?redirect=/platform`（`PlatformView.vue:217-222`），无开放重定向参数。
11. **运营台前端守卫与后端解耦**：路由 `requiresAdmin`（`router/index.ts:233-246`）只是 UX，`isAdmin` 来自服务端 `/users/me`（`stores/user.ts:27`），非本地可改；后端仍有独立 `requireAdminId`（见 1）。
12. **平台账号不可登录/无管理员位**：`sql/migration-platform.sql:160-168` 为 `PLATFORM_OFFICIAL` 占位手机号，登录用 `^1[3-9]\d{9}$` 校验（`LoginDTO.java:10`），无法通过短信登录；`role_flags=16` 与管理员位 8 不交叉（`AdminAuthService.java:40`）。

---

## ⑤ 验证方式与纪律说明

- 本次为**纯静态审计**：审计时 `8080/8081/8082/8083/8088/8848/6380` 全部未监听（`Get-NetTCPConnection` 全部 free），`docker ps` 为空（Nacos/Redis/RabbitMQ/Seata/Sentinel 均未运行），因此**未发起任何 HTTP 请求、未执行 PoC、未领券/抢购/下单/改库存/刷新缓存/写库**，仅读取源码/配置/仓库元数据（`git ls-files`、`git grep`）。
- 行号以当前工作区 HEAD `681a5e5` 的源码为准；工具间换行处理偶有 ±1~2 行差异，阅读时以文件内容定位为准。
- “验证状态=静态确认”表示：结论由代码路径 + 表结构 + 框架机制（Sa-Token 拦截器、MySQL 条件更新、Lua 原子脚本、Redis key 命名）推断成立；**未做运行时复现**。
- 建议修复轮在预发环境（需 Lead 批准）按以下顺序做**只读/低风险**复现：
  1. SEC-PLT-01：`GET /nacos/v1/cs/configs?dataId=...` 读取配置验证未授权（只读），不在真实配置上 POST。
  2. SEC-PLT-02/03：用测试环境只读查询 `GET /users/me` 带伪造头验证（禁止调 lock 写接口）；lock 接口仅在测试商品上由 owner 用可回滚方式验证。
  3. SEC-PLT-04/05：在测试库/测试账号上走“抢购→取消”与“超时关单”路径，验证 Redis `seckill:stock` 与 `platform_seckill_record` 变化（需 Redis 只读 `GET/HGETALL`，不直写）。

## ⑥ 未验证 / 不确定

1. **网关白名单与路由路径规范化差异**（SEC-PLT-15）：仅静态发现口径不一致，未构造编码斜杠/`..`/矩阵参数做绕过测试；Spring Boot 3 默认拒绝可疑路径，因此不计入已确认漏洞，建议安全单测覆盖。
2. **缓存重建竞态窗口**（SEC-PLT-10）：并发 claim + `warmup(true)` 的精确窗口未压测；券“超发”结论依赖“DB 无发放计数约束”的代码事实，未做并发复现。
3. **云版端口暴露范围**（SEC-PLT-02/03）：取决于部署方式（本机/容器/局域网），本次只确认“未配置 `server.address` 内网绑定 + 无内部鉴权”，未从其它主机做网络探测。
4. **平台商品云副本漂移**（SEC-PLT-20）：迁移脚本按公共列复制，实际迁移是否已执行/执行时数据快照时间未核对（审计时 DB 未运行）。
5. **App 端**：仅审计平台页复用的 ApiClient/token/服务器地址配置（与 T37-app 交叉），未审计 Flutter 本地缓存中券/订单数据的落盘加密。
6. **运营台界面交互**（如删除代卖点/下架商品二次确认）按代码与注释判断，未在浏览器运行验证；不影响鉴权结论。

## ⑦ 建议的修复排期（给 Lead）

| 批次 | 条目 | 说明 |
| --- | --- | --- |
| 第 1 批（P0，必须） | SEC-PLT-01 | Nacos 鉴权+网络收敛、凭据环境变量化与轮换、仓库历史清理 |
| 第 2 批（P1，云边界） | SEC-PLT-02、03 | 服务只监听内网/网关+Feign ACL、内部签名头、lock 接单幂等键绑定；不发布 8081-8083 |
| 第 3 批（P1，平台业务） | SEC-PLT-04、05、06 | 取消/超时统一补偿（含秒杀 Redis+记录）、平台单超时关单、关闭 mock 验证码/加风控；补 Redis/DB 对账告警 |
| 第 4 批（P1/P2，凭据与安全面） | SEC-PLT-07、08、09、13、17 | App HTTPS/Keystore/地址白名单；凭据治理；平台库存迁独立 db；限流统一+fail-closed；中间件端口收敛 |
| 第 5 批（P2） | SEC-PLT-10、11、12、14、15、16、18、19、20 | 缓存重建原子化/频控、秒杀 key 加场次、下架联动关单、公开接口限流脱敏、路径 matcher 加固、管理操作审计、token 存储与 CSP、图片上传白名单、云版平台数据同步 |
| 第 6 批（P3） | SEC-PLT-21～25 | 限购配置一致性、CORS 收敛、缓存统计脱敏、seller-id 启动校验、镜像非 root |

---

> 附：本报告为只读审计产物，未修改任何源码/配置/数据库；行号对应 HEAD `681a5e5`。跨模块引用已在条目中标注（T34 交易闭环、T37 App 移动端），避免重复计分。
