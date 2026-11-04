# 09 · 微服务架构与 UML（拆分后）

> 代码位置：`ershou-cloud`。定位是**绞杀者模式的渐进式迁移切片**：已迁移路径走新服务，未迁移路径由网关反代回旧单体 `:8080`，前端只改 baseURL 端口即可。
>
> 端口：网关 **8088**｜user **8081**｜item **8082**｜order **8083**｜旧单体 **8080**。

---

## 1. 部署架构

```mermaid
flowchart TB
    subgraph Client["客户端"]
        W["Web 前端"]
        A["移动端 App"]
    end

    G["ershou-cloud-gateway :8088<br/>Sa-Token 鉴权 / Sentinel 限流 / 路由"]

    subgraph Svc["微服务"]
        U["ershou-user-service :8081<br/>认证 / 用户 / 学校校区"]
        I["ershou-item-service :8082<br/>商品读 / 内部锁库存 / MQ 消费"]
        O["ershou-order-service :8083<br/>面交单 / Seata TM / MQ 生产"]
    end

    L["旧单体 ershou-server :8080<br/>未迁移模块"]

    subgraph Mid["中间件"]
        N["Nacos :8848<br/>注册 + 配置"]
        MQ["RabbitMQ :5672"]
        SE["Seata TC :8091"]
        SN["Sentinel :8858"]
        R[("Redis :6380<br/>db0 会话 / db1 user / db2 item / db3 order")]
        subgraph MySQL["MySQL :3306"]
            DB0[("ershou 旧库")]
            DBU[("ershou_user")]
            DBI[("ershou_item")]
            DBO[("ershou_order")]
        end
    end

    W --> G
    A --> G
    G -->|lb://| U
    G -->|lb://| I
    G -->|lb://| O
    G -->|反代| L
    U --> N
    I --> N
    O --> N
    G --> N
    U --> DBU
    I --> DBI
    O --> DBO
    L --> DB0
    U --> R
    I --> R
    O --> R
    G --> R
    O -->|Feign 锁库存| I
    O -->|order.created| MQ
    MQ -->|消费| I
    O --- SE
    I --- SE
    G --- SN
```

---

## 2. 网关路由表（[GatewayRoutesConfig.java](../ershou-cloud/ershou-cloud-gateway/src/main/java/com/ershou/cloud/gateway/config/GatewayRoutesConfig.java)）

按**声明顺序**匹配，特例必须放在通配之前。

| 顺序 | 路由 ID | 匹配 | 目标 |
| --- | --- | --- | --- |
| 1 | ershou-user-auth | `/api/v1/auth/**` | `lb://ershou-user-service` |
| 2 | ershou-user-schools | `/api/v1/schools/**` | `lb://ershou-user-service` |
| 3 | ershou-user-me | `/api/v1/users/me` | `lb://ershou-user-service` |
| 4 | ershou-user-legacy | `/api/v1/users/**` | 旧单体 `8080` |
| 5 | ershou-item-categories | `/api/v1/categories`、`/api/v1/categories/**` | `lb://ershou-item-service` |
| 6 | ershou-item-mine | `GET /api/v1/items/mine` | 旧单体 `8080` |
| 7 | ershou-item-get | `GET /api/v1/items/**` | `lb://ershou-item-service` |
| 8 | ershou-item-write | `/api/v1/items/**`（非 GET） | 旧单体 `8080` |
| 9 | ershou-order-meetup | `/api/v1/meetup-orders/**` | `lb://ershou-order-service` |
| 10 | ershou-legacy-ws | `/ws/**` | `ws://127.0.0.1:8080` |
| 11 | ershou-legacy-api | `/api/v1/**`（兜底） | 旧单体 `8080` |

> 已知缺口：order-service 只实现了 `POST /meetup-orders`（创建）和 `GET /meetup-orders/mine`；而路由把整个 `/meetup-orders/**` 都转给 order-service，所以 `/{id}`、`/{id}/accept`、`/todo-count` 等经网关会 **404**。实现时可补齐这些路由，或把它们显式反代回单体。

---

## 3. 鉴权与身份透传

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant G as 网关 SaReactorFilter
    participant F as AuthGlobalFilter
    participant S as 下游服务
    participant R as Redis db0

    C->>G: 请求 Authorization: Bearer token
    G->>R: 校验 token（与单体同 db0 同 key 空间）
    alt 未登录且非公开路径
        G-->>C: HTTP 401 {"code":10002,...}
    end
    G->>G: StpUtil.getLoginIdAsLong() → exchange attribute
    F->>F: 先删除客户端自带的 X-User-Id
    F->>S: 写入真实 X-User-Id 请求头
    S->>S: UserContextInterceptor 读头 → UserContext
    S-->>C: 业务响应
```

要点：
- 网关用 `SaReactorFilter`（**WebFilter 阶段**）做鉴权，因为 Reactor 下 `SaTokenContext` 是 ThreadLocal，GlobalFilter 里不能直接调 `StpUtil`。
- `AuthGlobalFilter`（GlobalFilter，order=-100）只负责把 userId 写成 `X-User-Id`，并**清除客户端伪造的同名头**。
- 下游服务通过 common 的 `UserContextInterceptor` 读 `X-User-Id`，业务代码用 `UserContext.requireUserId()`。

公开路径规则见 [PublicPaths.java](../ershou-cloud/ershou-cloud-gateway/src/main/java/com/ershou/cloud/gateway/config/PublicPaths.java)：`/auth/**`、GET 的 schools/categories/map/trade-points/platform/shop/search status/商品读/用户主页、`/actuator/health`、`/ws/**`、OPTIONS。

---

## 4. 数据隔离

| 服务 | MySQL 库 | Redis db | 业务 key 前缀 |
| --- | --- | --- | --- |
| gateway | 无 | db0 | 会话 `Authorization:*` |
| user-service | `ershou_user` | db0（Sa-Token 会话）+ db1（验证码/限流） | `ershou:user:` |
| item-service | `ershou_item` | db2 | `ershou:item:` |
| order-service | `ershou_order` | db3 | `ershou:order:` |
| 旧单体 | `ershou` | db0 | 原有 key |

> user-service 同时需要 db0 和 db1：业务验证码按设计落 db1，而 Sa-Token 会话必须落 db0 才能与其他服务互通，所以 [SaTokenRedisConfig.java](../ershou-cloud/ershou-user-service/src/main/java/com/ershou/cloud/user/config/SaTokenRedisConfig.java) 单独建了一个指向 db0 的连接工厂。

---

## 5. 关键类图

### 5.1 order-service（事务编排方）

```mermaid
classDiagram
    class MeetupOrderController {
        +create(CreateOrderDTO) Result~OrderVO~
        +mine(Integer status, long pageNum, long pageSize) Result~PageResult~OrderVO~~
    }
    class InnerOrderController {
        +get(Long id) Result~OrderVO~
    }
    class MeetupOrderService {
        +create(Long userId, CreateOrderDTO dto) OrderVO
        +mine(Long userId, Integer status, long, long) PageResult~OrderVO~
    }
    class OrderEventPublisher {
        +publishOrderCreated(OrderCreatedEvent) void
    }
    class ItemClient {
        +get(Long id) Result~InnerItemVO~
        +lock(Long id, Integer qty) Result~Integer~
    }
    class ItemClientFallback
    class MeetupOrder
    class MeetupOrderMapper

    MeetupOrderController --> MeetupOrderService
    InnerOrderController --> MeetupOrderService
    MeetupOrderService --> MeetupOrderMapper
    MeetupOrderService --> ItemClient
    MeetupOrderService --> OrderEventPublisher
    ItemClient ..> ItemClientFallback : fallback
    MeetupOrderMapper --> MeetupOrder
```

`MeetupOrderService.create` 标注 `@GlobalTransactional(name="create-meetup-order")`：
1. 校验商品（Feign `GET /inner/items/{id}`）
2. Feign `PUT /inner/items/{id}/lock` 条件扣库存（item 本地事务注册为 AT 分支）
3. 插入面交单（本服务本地事务，AT 分支）
4. 发布 `order.created` 事件
5. `debugFail=true` 时抛异常 → **全局回滚**

### 5.2 item-service

```mermaid
classDiagram
    class ItemQueryController {
        +list(ItemQueryDTO) Result~PageResult~ItemVO~~
        +detail(Long id) Result~ItemVO~
        +categories() Result~List~CategoryVO~~
    }
    class InnerItemController {
        +get(Long id) Result~InnerItemVO~
        +lock(Long id, Integer qty) Result~Integer~
    }
    class ItemQueryService
    class ItemLockService {
        +lock(Long itemId, Integer qty) int
        +evictDetail(Long itemId) void
    }
    class OrderCreatedListener {
        +onOrderCreated(Message, Channel) void
    }
    class ItemMapper
    class Item

    ItemQueryController --> ItemQueryService
    InnerItemController --> ItemLockService
    ItemLockService --> ItemMapper
    ItemQueryService --> ItemMapper
    OrderCreatedListener --> ItemMapper
    ItemMapper --> Item
```

- `ItemLockService.lock`：单条**条件 UPDATE** 扣库存（`where stock >= qty and status=2`），返回 1/0，不抛异常，便于 Feign 判空；方法带 `@Transactional` 以参与 Seata AT。
- `OrderCreatedListener`：手动 ack + 死信队列 + Redis SETNX 幂等。

### 5.3 user-service

```mermaid
classDiagram
    class AuthController {
        +smsCode(SmsCodeDTO) Result~Void~
        +login(LoginDTO) Result~LoginVO~
        +logout() Result~Void~
    }
    class UserController {
        +me() Result~UserVO~
    }
    class SchoolController
    class UserService {
        +sendSmsCode(String phone) void
        +login(LoginDTO) LoginVO
        +logout() void
    }
    class SysUserMapper
    class SchoolMapper
    class CampusMapper
    class PhoneCrypto

    AuthController --> UserService
    UserController --> UserService
    UserService --> SysUserMapper
    UserService --> SchoolMapper
    UserService --> CampusMapper
    UserService --> PhoneCrypto
```

---

## 6. 关键时序图

### 6.1 下单：Seata AT 全局事务

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant G as 网关
    participant O as order-service
    participant I as item-service
    participant TC as Seata TC
    participant DBU as ershou_order
    participant DBI as ershou_item
    participant MQ as RabbitMQ

    C->>G: POST /api/v1/meetup-orders
    G->>O: 转发（带 X-User-Id）
    O->>O: @GlobalTransactional 开启全局事务（向 TC 注册）
    O->>I: Feign GET /inner/items/{id} 校验商品
    O->>I: Feign PUT /inner/items/{id}/lock {qty}
    I->>DBI: UPDATE item SET stock=stock-qty WHERE stock>=qty AND status=2
    I->>I: 清 Redis db2 详情缓存
    I-->>O: {code:0, data:1}
    O->>DBU: INSERT meetup_order(status=1)
    O->>MQ: 发布 order.created
    alt debugFail=true 或任意异常
        O->>TC: 全局回滚
        TC->>DBI: 用 undo_log 前镜像还原 stock/status
        TC->>DBU: 回滚订单插入
        O-->>C: 90001 回滚成功
    else 正常
        O->>TC: 全局提交
        O-->>C: {orderNo, status=1}
    end
```

### 6.2 RabbitMQ：下单事件 → 销量累加

```mermaid
sequenceDiagram
    autonumber
    participant O as order-service
    participant MQ as RabbitMQ
    participant D as 死信队列
    participant I as item-service
    participant R as Redis db2
    participant DB as ershou_item

    O->>MQ: publish order.created {orderId,itemId,quantity,...}
    MQ->>I: deliver（手动 ack 模式）
    I->>R: SETNX ershou:item:mq:order:{orderId} EX 24h
    alt 已消费过
        I->>MQ: basicAck（跳过）
    end
    I->>DB: UPDATE item SET sale_count=sale_count+qty
    I->>R: DEL 商品详情缓存
    I->>MQ: basicAck
    alt 处理失败
        I->>R: DEL 幂等 key（允许重投时重试）
        I->>MQ: basicNack(requeue=false) → 进死信 ershou.order.dlx
    end
```

### 6.3 登录（经网关）

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant G as 网关
    participant U as user-service
    participant R1 as Redis db1
    participant R0 as Redis db0
    participant DB as ershou_user

    C->>G: POST /api/v1/auth/sms-code {phone}
    G->>U: 公开路径放行
    U->>R1: SET ershou:user:sms:code:{phone} EX 300（含每日 10 次限流）
    U-->>C: Result.ok()

    C->>G: POST /api/v1/auth/login {phone, code}
    G->>U: 转发
    U->>R1: 比对验证码（或 mock 码直接命中）
    U->>DB: SELECT sys_user WHERE phone_hash=SHA256(phone)
    alt 不存在
        U->>DB: INSERT 新用户
    end
    U->>R0: StpUtil.login(userId)（db0，与单体/网关共享）
    U-->>C: {token, user}

    C->>G: GET /api/v1/users/me (Bearer token)
    G->>R0: 校验 token
    G->>U: 带 X-User-Id 转发
    U-->>C: UserVO
```

---

## 7. 端口与配置

| 端口 | 服务 | 说明 |
| --- | --- | --- |
| 8088 | gateway | 唯一对外入口 |
| 8081 | user-service | 认证/用户/学校 |
| 8082 | item-service | 商品读 + `/inner/**` |
| 8083 | order-service | 面交单 + Seata TM + MQ 生产 |
| 8080 | 旧单体 | 未迁移路径反代目标 + WebSocket |
| 8848 / 9848 | Nacos | 注册 + 配置 |
| 5672 / 15672 | RabbitMQ | AMQP / 管理台 |
| 8091 / 7091 | Seata | TC / 控制台 |
| 8858 | Sentinel | Dashboard |
| 6380 | Redis | db0–db3 |
| 3306 | MySQL | `ershou` + `ershou_user/item/order` |

**配置来源**：每个服务本地 `application.yml` 只留端口/服务名/Nacos 地址，数据源/Redis/RabbitMQ/Seata 全部从 Nacos 拉（4 个 dataId：`ershou-gateway.yaml`、`ershou-user-service.yaml`、`ershou-item-service.yaml`、`ershou-order-service.yaml`，见 `ershou-cloud/nacos-configs/`）。

---

## 8. 已知取舍（实现时照做即可，也可改进）

| 项 | 现状 |
| --- | --- |
| 新旧库同步 | **一次性快照迁移**，之后旧单体写旧库、新服务写新库，不自动同步 |
| Seata 版本 | client 2.1.0 / server 1.8.0，存在兼容风险 |
| Seata 容器 IP | TC 在 Nacos 注册的是 Docker 内网 IP，宿主机客户端可能连不上 |
| 迁移范围 | 仅认证、商品读、下单；商品写、聊天、购物车、运营、平台仍在单体 |
| 会话 | 全部服务共享 Redis db0，单点登录态 |
| 降级 | order→item 的 Feign 配了 Sentinel fallback，item 不可用时返回"商品服务暂不可用" |
