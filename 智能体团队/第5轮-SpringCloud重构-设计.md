# 第 5 轮 · Spring Cloud 微服务重构（演示版）设计与开发契约

> 版本：v1（2026-10-19）｜作者：`lead`
> 用户需求："使用智能体团队把项目用 Spring Cloud 重构，目前开发主要是功能和技术演示；Redis 用现有 Redis 但注意数据隔离；数据库要做好拆分；用 RabbitMQ 优化；选 Nacos、Sentinel、Seata、Spring AMQP、OpenFeign、Spring Cloud Gateway；用 Docker。"
> **本轮定位：可运行、可演示的微服务化切片 + 完整中间件编排 + 迁移路线图**，不追求一次性迁移全部业务（绞杀者模式：未迁移路径由网关反代到原单体，功能不中断）。

---

## 一、总体架构

```
                    ┌────────────────────────── Web (5173) / App (8088) ──────────────────────────┐
                    │                        Spring Cloud Gateway :8088                          │
                    │  Sa-Token 鉴权(共享 Redis db0) · Sentinel 限流 · 路由/反代 · X-User-Id 透传      │
                    └───────┬──────────────────────┬─────────────────────┬────────────────────────┘
                            │ /api/v1/auth|users   │ /api/v1/items        │ /api/v1/meetup-orders
                            │ /api/v1/schools      │ (GET)                │ + 其余全部 /api/v1/**、/ws/**
                            ▼                      ▼                      ▼                        ▼
                   user-service :8081     item-service :8082     order-service :8083      legacy monolith :8080
                   MySQL ershou_user      MySQL ershou_item      MySQL ershou_order       MySQL ershou（原库，不动）
                   Redis db1              Redis db2              Redis db3                Redis db0（会话）
                        │                      │  ▲                    │  │
                        │                      │  └── RabbitMQ ◀───────┘  │
                        │                      │   order.created 事件     │
                        │                      └──── OpenFeign（库存查询/锁定，Seata AT）◀──┘
                    ├────────────── 中间件（Docker）──────────────┤
                    │ Nacos :8848（注册+配置）  RabbitMQ :5672/:15672  │
                    │ Seata TC :8091  Sentinel Dashboard :8858       │
                    └────────────────────────────────────────────────┘
```

**版本矩阵（已在本机验证可解析/可拉取）**
| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.3.5（与单体一致） |
| Spring Cloud | 2023.0.3 |
| Spring Cloud Alibaba | 2023.0.3.2（nacos-client 2.3.x / sentinel 1.8.x / seata-client 1.8.0） |
| Sa-Token | 1.39.0（`sa-token-reactor-spring-boot3-starter` + `sa-token-redis-jackson`，与单体同版本、同 Redis db0、同 token-name → **会话互通**） |
| MyBatis-Plus | 3.5.9（同单体） |
| Java | 21 |
| Docker 镜像 | `nacos/nacos-server:v2.3.2`（已拉取）、`rabbitmq:3-management`（本地已有）、`seataio/seata-server:1.8.0`（拉取中）、`eclipse-temurin:17-jre`（拉取中，自建 sentinel-dashboard 镜像） |

**端口分配**
| 服务/中间件 | 端口 | 说明 |
| --- | --- | --- |
| `ershou-gateway` | **8088** | 统一入口；前端/App 只认它 |
| `ershou-user-service` | 8081 | 认证/用户/学校校区 |
| `ershou-item-service` | 8082 | 商品读 + 内部库存锁定 + MQ 消费 |
| `ershou-order-service` | 8083 | 面交单 + Feign + Seata + MQ 生产 |
| 旧单体 `ershou-server` | 8080 | 未迁移模块（admin/platform/map/chat/files/search/cart/favorites 写路径） |
| Nacos | 8848（+9848 gRPC） | 注册中心 + 配置中心 |
| RabbitMQ | 5672 / 15672 | AMQP / 管理台（guest/guest） |
| Seata Server | 8091（+7091 控制台） | TC，registry=nacos，store=file（演示） |
| Sentinel Dashboard | 8858 | 本地 jar 自建镜像（DockerHub 镜像 403 不可用） |

---

## 二、数据拆分与隔离（**硬要求**）

### 2.1 MySQL 分库（同一个 MySQL 实例、**独立 schema + 独立账号**，改 URL 即可物理拆分）
| 库 | 账号（密码统一 `__REDACTED_DB_PASSWORD__`） | 表（本轮） |
| --- | --- | --- |
| `ershou_user` | `ershou_user` | `sys_user`、`school`、`campus` |
| `ershou_item` | `ershou_item` | `item`（新增 `sale_count INT DEFAULT 0`）、`item_category`、`undo_log`（Seata AT 必需） |
| `ershou_order` | `ershou_order` | `meetup_order`、`undo_log` |
| `ershou`（原库） | `ershou` | 保持不动，供单体与未迁移模块使用；新服务**只读**它的数据做一次性迁移 |

- DDL/建账号：`ershou-cloud/sql/init-cloud-databases.sql`（幂等）。
- 数据迁移：`ershou-cloud/sql/migrate-data.ps1`（从 `ershou` 复制 `sys_user/school/campus/item/item_category/meetup_order` 到新库；保留原 id；可重复执行 = 先 DELETE/TRUNCATE 目标表再 INSERT SELECT）。
- **禁止跨库 JOIN**；跨服务数据通过 OpenFeign 获取（如订单列表补商品标题/封面）。
- 旧库数据是**快照**：迁移后新服务的写操作不会回写旧库（演示定位，文档注明；生产应做双写/CDC）。

### 2.2 Redis 隔离（沿用现有 Redis 6380，同一密码）
| 用途 | DB | key 前缀 | 说明 |
| --- | --- | --- | --- |
| Sa-Token 会话（单体+网关+user-service 共享） | **0** | `Authorization:login:*`（Sa-Token 自带） | **必须共享 db0**，否则登录态不互通；这是唯一允许跨服务共用的 db |
| user-service 验证码/限流 | **1** | `ershou:user:` | 短信码、登录限流 |
| item-service 缓存/浏览量 | **2** | `ershou:item:` | 商品详情缓存、浏览量计数 |
| order-service 幂等/锁 | **3** | `ershou:order:` | 下单幂等 key、订单号序列 |

- 每服务 `spring.data.redis.database` 指向自己的 DB；所有业务 key 必须带自己前缀（禁止裸 key）。
- 验收断言：`--scan` 各 db 只出现自己的前缀 + db0 只有 `Authorization:*`。

---

## 三、Maven 模块结构（根目录 `ershou-cloud/`）

```
ershou-cloud/
├── pom.xml                          # parent：Boot3.3.5 + Cloud2023.0.3 + SCA2023.0.3.2 + 模块与版本管理
├── ershou-cloud-common/             # 公共包（jar，被 4 个服务依赖）
│   └── src/main/java/com/ershou/cloud/common/
│       ├── result/{Result,ResultCode,PageResult}.java      # 与单体同结构：{code,message,data,traceId}
│       ├── exception/{BizException,GlobalExceptionHandler}.java
│       ├── web/{TraceIdFilter,UserContext(ThreadLocal),UserContextInterceptor,ApiHeaderSupport}.java
│       └── util/{PhoneCrypto,Ids}.java
├── ershou-cloud-gateway/            # :8088
│   └── src/main/java/com/ershou/cloud/gateway/
│       ├── GatewayApplication.java
│       ├── config/{SaTokenReactorConfig,SentinelGatewayConfig,CorsConfig,GatewayRoutesConfig}.java
│       └── filter/{AuthGlobalFilter(UserIdHeaderFilter),TraceIdGlobalFilter}.java
├── ershou-user-service/             # :8081
│   └── .../user/{UserApplication, controller(AuthController,UserController,SchoolController),
│                service, mapper, entity(SysUser,School,Campus), config(SaTokenConfig,MybatisConfig)}
├── ershou-item-service/             # :8082
│   └── .../item/{ItemApplication, controller(ItemQueryController,InnerItemController),
│                  service(ItemQueryService,ItemLockService), mapper, entity(Item,ItemCategory),
│                  mq(OrderCreatedListener), config(RedisConfig,RabbitConfig,SeataConfig,ItemCacheKeys)}
├── ershou-order-service/            # :8083
│   └── .../order/{OrderApplication, controller(MeetupOrderController),
│                  service(MeetupOrderService,OrderEventPublisher), mapper, entity(MeetupOrder),
│                  feign(ItemClient, ItemClientFallback), config(RabbitConfig,SeataConfig,SentinelConfig),
│                  dto/{CreateOrderDTO,OrderVO}}
├── docker/
│   ├── docker-compose.yml           # nacos / rabbitmq / seata / sentinel-dashboard
│   ├── seata/{registry.conf,file.conf}          # Seata 1.8.0 配置（registry=nacos, store=file）
│   ├── sentinel/{Dockerfile,sentinel-dashboard.jar}  # 自建 dashboard 镜像（jar 由脚本下载）
│   ├── Dockerfile.service           # 通用服务镜像（ARG JAR_FILE），构建脚本演示 gateway 镜像
│   └── README.md
├── sql/{init-cloud-databases.sql, migrate-data.ps1}
├── docs/{architecture.md, demo-script.md, migration-roadmap.md}
└── nacos-configs/{ershou-gateway.yaml, ershou-user-service.yaml, ershou-item-service.yaml, ershou-order-service.yaml}
```

**每个服务本地 `application.yml` 只保留**：`server.port`、`spring.application.name`、Nacos 地址（`NACOS_ADDR:127.0.0.1:8848`）与 `spring.config.import: optional:nacos:<name>.yaml?group=DEFAULT_GROUP&refreshEnabled=true`；数据源/Redis/RabbitMQ/Seata 等全部放 Nacos 配置里（配置中心演示点）。

---

## 四、接口与路由契约（**前端无需改代码，只改 baseURL/代理端口**）

### 4.1 Gateway 路由（统一保留 `/api/v1` 前缀）
| 路由 | 目标 | 备注 |
| --- | --- | --- |
| `/api/v1/auth/**`（POST） | `lb://ershou-user-service` | 公开 |
| `/api/v1/users/**` | `lb://ershou-user-service` | 需登录 |
| `/api/v1/schools/**`、`/api/v1/categories` | `lb://ershou-user-service` | 公开（登录后可带态） |
| `GET /api/v1/items/**` | `lb://ershou-item-service` | 公开 |
| `POST/PUT/DELETE /api/v1/items/**` | `http://127.0.0.1:8080` | 写路径未迁移，反代单体 |
| `/api/v1/meetup-orders/**` | `lb://ershou-order-service` | 需登录（创建/我的列表已迁移） |
| 其余 `/api/v1/**`（admin/platform/map/files/search/cart/favorites/...） | `http://127.0.0.1:8080` | 绞杀者过渡，功能不中断 |
| `/ws/**` | `ws://127.0.0.1:8080` | WebSocket 暂留单体 |

### 4.2 user-service
- `POST /api/v1/auth/sms-code` body `{phone}` → mock 码固定 `123456` 存 Redis **db1** `ershou:user:sms:code:{phone}` TTL 5min；返回 `Result<Void>`
- `POST /api/v1/auth/login` body `{phone,code}` → 校验（mock 码或 Redis 码）；不存在则创建用户（同单体逻辑：AES 手机号 + phone_hash）；`StpUtil.login(id)`（写 **db0**，与单体互通）；返回 `{token, user:{...}}`（字段与单体 `/auth/login` 完全一致，Web/App 不用改）
- `POST /api/v1/auth/logout` → `StpUtil.logout()`；`GET /api/v1/users/me`（网关 `X-User-Id`）→ 返回单体的 user 字段结构
- `GET /api/v1/schools`、`GET /api/v1/schools/{schoolId}/campuses`

### 4.3 item-service
- `GET /api/v1/items?pageNum&pageSize&keyword&categoryId&sort=latest|price_asc|price_desc` → `PageResult`，只返回 `status=2`；VO 字段与单体 ItemVO 对齐（Web 首页可直接用）
- `GET /api/v1/items/{id}` → 详情（Redis db2 缓存 `ershou:item:detail:{id}` 60s + 浏览量 `INCR ershou:item:view:{id}`）
- 内部接口（不经过网关，仅供 Feign）：`GET /inner/items/{id}` → `{id,title,coverUrl,price,stock,status,sellerId}`；`PUT /inner/items/{id}/lock?qty=` → 条件扣库存（`status=2 AND stock>=qty`，返回 0/1），**参与 Seata AT**
- MQ 消费：`queue: ershou.item.order-created.queue`，binding `ershou.order.topic / order.created`；收到 `{orderId,itemId,quantity,userId}` → `UPDATE item SET sale_count = sale_count + quantity`（演示 RabbitMQ 异步解耦）+ 清详情缓存 + `log.info`

### 4.4 order-service
- `POST /api/v1/meetup-orders` body `{itemId, quantity, remark, debugFail}`（debugFail 仅演示用）
  - `@GlobalTransactional`（Seata AT）：Feign `GET /inner/items/{id}` 校验 → Feign `PUT .../lock` → 插入 `meetup_order`（status=2 待面交、seller_confirm_at=now、order_no 生成）→ debugFail=true 时抛 `BizException` → 全局回滚（item 库存回滚、订单不落库）
  - 事务提交后发 MQ `order.created`（`RabbitTemplate` + `@TransactionalEventListener(AFTER_COMMIT)`）
  - 返回 `Result<OrderVO>`
- `GET /api/v1/meetup-orders/mine?status=` → 当前用户面交单分页；商品标题/封面通过 Feign `GET /inner/items/{id}` 补全（演示服务间数据拼装）
- 内部：`GET /inner/orders/{id}`（调试用）

### 4.5 Sentinel
- Gateway：`/api/v1/items` 路由 QPS=5 限流规则（代码加载 + Dashboard 可见），超限返回 `429 {"code":429,"message":"请求过于频繁，请稍后重试"}`。
- order-service：Feign `ItemClient` 开启 Sentinel（`feign.sentinel.enabled=true`），fallback 类在 item-service 不可用时返回业务降级（`code=90001,"商品服务暂不可用"`）。
- Sentinel Dashboard :8858 可视化；规则代码内置（演示可改）。

### 4.6 Nacos 配置内容（`ershou-cloud/nacos-configs/*.yaml`，用手册脚本 PUT 到 Nacos）
- `ershou-user-service.yaml`：datasource(ershou_user)、redis(db1)、mybatis-plus、sa-token、日志
- `ershou-item-service.yaml`：datasource(ershou_item)、redis(db2)、rabbitmq、seata client、mybatis-plus
- `ershou-order-service.yaml`：datasource(ershou_order)、redis(db3)、rabbitmq、seata client、feign/sentinel、业务参数
- `ershou-gateway.yaml`：sa-token（token-name Authorization、redis db0）、sentinel 规则、路由超时（路由本身代码定义，配置文件放可动态参数）
- 演示点：修改 Nacos 里 `ershou.order.max-qty` 或 `ershou.item.page-max` 后 `@RefreshScope` 立即生效。

---

## 五、Seata / RabbitMQ 关键配置约定

### 5.1 Seata（client 1.8.0 ↔ server 1.8.0）
- Server：`docker/seata/registry.conf`（`registry type=nacos`、`config type=file`、serverAddr nacos:8848、application=seata-server、group=SEATA_GROUP、cluster=default）+ `file.conf`（`store.mode=file`）；`docker-compose` 挂载到 `/seata-server/resources/`，端口 8091/7091。
- Client（item/order）Nacos 配置：
```yaml
seata:
  enabled: true
  application-id: ${spring.application.name}
  tx-service-group: ershou_tx_group
  service:
    vgroup-mapping:
      ershou_tx_group: default
  registry:
    type: nacos
    nacos: { server-addr: 127.0.0.1:8848, application: seata-server, group: SEATA_GROUP, username: nacos, password: nacos }
  config:
    type: file
  data-source-proxy-mode: AT
```
- `undo_log` 表必须存在于 `ershou_item` 与 `ershou_order`（DDL 在 init sql）。
- **Seata AT 要求**：业务方法 `@GlobalTransactional` 必须覆盖 Feign 调用（TM 在 order-service）；锁接口需 `@Transactional` 但不要自己加 `Propagation.REQUIRES_NEW`。

### 5.2 RabbitMQ
- `spring.rabbitmq`: host 127.0.0.1:5672, guest/guest；`spring.rabbitmq.template.retry.enabled=true`。
- Exchange：`ershou.order.topic`（topic，durable）；Queue：`ershou.item.order-created.queue`（durable，`x-dead-letter-exchange=ershou.order.dlx`）；Binding：`order.created`。
- Producer（order-service）：事务提交后发消息（JSON 字符串）；消息体 `{orderId,orderNo,itemId,buyerId,quantity,createdAt}`
- Consumer（item-service）：`@RabbitListener`，手动 ack（`acknowledge-mode: manual`，失败 nack 不重回队列进死信）；幂等：`ershou:item:mq:order:{orderId}` SETNX 防重复消费（Redis db2）。

---

## 六、文件所有权矩阵（严格，禁止跨范围写）

| 成员 | 任务 | 可写范围 |
| --- | --- | --- |
| `platform-dev` | T24 父工程+common+gateway+Nacos 配置模板 | `ershou-cloud/pom.xml`、`ershou-cloud/ershou-cloud-common/**`、`ershou-cloud/ershou-cloud-gateway/**`、`ershou-cloud/nacos-configs/**` |
| `admin-member` | T25 user-service | `ershou-cloud/ershou-user-service/**` |
| `item-member` | T26 item-service | `ershou-cloud/ershou-item-service/**` |
| `order-member` | T27 order-service | `ershou-cloud/ershou-order-service/**` |
| `chat-member` | T28 Docker/DB/脚本 | `ershou-cloud/docker/**`、`ershou-cloud/sql/**`、`scripts/cloud-*.ps1`（新建） |
| `map-member` | T29 前端接网关 + 端到端演示脚本 | `ershou-web/vite.config.ts`（仅代理目标 env 化）、`ershou-cloud/docs/**`（演示脚本部分）、`scripts/cloud-demo.ps1`（新建） |
| `app-member` | T30 文档（架构图/迁移路线图/演示手册） | `ershou-cloud/docs/**`（除演示脚本）、`ershou-cloud/README.md`（新建） |
| `lead` | 整合/迁移落库/最终验收/项目文档收口 | `ershou-cloud/sql/migrate-data.ps1` 执行、根项目 README/开发日志/功能清单/团队 README |

> 冲突规则：同文件多人都要改时先报 lead 排期；`ershou-cloud/**/pom.xml` 只有 platform-dev 能改（其它服务需要新依赖 → 报 module 名称+artifact，由 platform-dev 加）。
> **旧单体 `ershou-server/**` 本轮一律不改**（除最终兼容性 bug 需 lead 批准）。

---

## 七、验收标准（QA + Lead 逐条给证据）
1. **Docker**：`docker compose -f ershou-cloud/docker/docker-compose.yml up -d` 后 nacos/rabbitmq/seata/sentinel 四容器 healthy；`docker ps` 证据；Redis 沿用现有 6380 容器。
2. **构建**：`mvn -f ershou-cloud/pom.xml -DskipTests package` 成功（5 模块）。
3. **启动/注册**：4 服务启动成功并注册 Nacos（`GET /nacos/v1/ns/service/list` 含 4 服务）；Nacos 配置中心 4 个 dataId 存在。
4. **数据库拆分**：`ershou_user/item/order` 三库各自表清单、账号独立；数据迁移后行数与旧库对应表一致（贴 SQL 输出）；旧库未被修改。
5. **网关链路**（全程经 8088）：`sms-code → login`（拿 token）→ `GET /users/me` 200 → `GET /items` 200（≥10 条）→ `GET /items/{id}` 200 → `POST /meetup-orders` 200（订单落库、item 库存减少）→ `GET /meetup-orders/mine` 200（含商品标题）；未迁移路径 `GET /api/v1/platform/shop/points` 经网关反代 8080 返回 200。
6. **Seata**：`POST /meetup-orders {debugFail:true}` → 返回失败；核对 item 库存**未减少**、meetup_order **无新行**（贴 DB 前后对比 + 日志中的 `Rollback`）。
7. **RabbitMQ**：正常下单后 3s 内 `item.sale_count` 增加；RabbitMQ 管理 API 显示队列消费数 + item-service 日志；重复消息幂等验证。
8. **Sentinel**：压测 `/api/v1/items`（并发 10）出现 429 限流响应；Dashboard 可见资源/规则。
9. **Nacos 配置动态**：改 Nacos 配置项（如 `ershou.order.max-qty`）后无需重启生效（日志/接口行为变化）。
10. **前端接入**：Web `npm run build` exit 0；`vite.config.ts` 代理可用 env 指向 8088；页面经网关联通（登录/首页/商品详情/面交单接口 200）。App 只需服务器地址填 `host:8088`（文档说明）。
11. **Redis 隔离**：`--scan --pattern 'ershou:*'` 在各 db 只出现自己前缀；db0 仅 Sa-Token key。
12. **文档**：`ershou-cloud/docs/architecture.md`（mermaid）、`demo-script.md`（一步步演示）、`migration-roadmap.md`（剩余模块迁移计划）；根 README/开发日志更新。

---

## 八、风险与降级预案
| 风险 | 预案 |
| --- | --- |
| Sentinel Dashboard 镜像 403 | 用 Maven Central 的 `sentinel-dashboard` jar + `eclipse-temurin` 自建镜像；再不行则规则代码内置、Dashboard 记为可选（技术点仍成立） |
| Seata 1.8.0 镜像拉取失败/配置复杂 | 保留 Seata 2.0.0 镜像（已拉取）改 client 2.0.0；再不行降级为"Seata 接入 + AT 配置就绪、rollback 演示用 TC 日志"，不阻塞其他验收 |
| SCA 与 Boot 3.3.5 兼容问题 | 已本地验证依赖可解析；若启动报版本冲突，platform-dev 统一在 parent 里 pin 版本 |
| 旧单体未启动导致反代 502 | 演示前先启动单体 8080（用户 IDEA 或脚本）；文档写明启动顺序 |
| 前端/移动端改动 | 网关保持 `/api/v1/**` 路径不变 → Web 只改代理端口，App 只改服务器地址，零代码改动 |
