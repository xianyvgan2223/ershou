# 01 · 单体架构与 UML（拆分微服务前）

> 代码位置：`ershou-server`，启动类 `CampusApplication`，端口 **8080**，所有接口前缀 `/api/v1`。
> 本文给出：系统上下文、分层结构、模块依赖、ER 图、核心类图、4 张关键时序图、2 张状态机。照着这些图就能把单体骨架搭出来。

---

## 1. 系统上下文

```mermaid
flowchart LR
    subgraph Client["客户端"]
        W["Web 前端<br/>Vue3 + Vite"]
        A["移动端 App<br/>Flutter"]
    end

    S["单体后端<br/>Spring Boot :8080<br/>/api/v1/**"]

    subgraph Middleware["中间件"]
        DB[("MySQL<br/>ershou 库")]
        RD[("Redis :6380<br/>db0 会话/业务缓存")]
        ES[("Elasticsearch<br/>:9211<br/>商品搜索")]
        MO[("MinIO<br/>:9010<br/>图片/视频")]
        AM["高德地图 Web API<br/>逆地理编码"]
    end

    W -->|HTTP / WS| S
    A -->|HTTP / WS| S
    S --> DB
    S --> RD
    S --> ES
    S --> MO
    S --> AM
```

- 单体**一个进程**装下全部业务，WebSocket 长连接也在这一个进程里。
- Redis 同时承担 **Sa-Token 会话**和业务缓存；ES 负责商品搜索；MinIO 负责文件。

---

## 2. 分层结构（每个 module 内部固定六层）

```mermaid
flowchart TB
    subgraph Module["一个业务模块（例如 module/item）"]
        direction TB
        C["controller<br/>接 HTTP、参数校验、返回 Result"]
        SV["service<br/>业务逻辑、事务边界"]
        M["mapper<br/>MyBatis-Plus，访问 DB"]
        E["entity<br/>与表一一对应"]
        D["dto<br/>入参对象"]
        V["vo<br/>出参对象"]
        C --> SV
        SV --> M
        M --> E
        C -.使用.-> D
        C -.使用.-> V
        SV -.转换.-> V
    end
    DB[("MySQL")] --- M
```

调用链固定是：`Controller → Service → Mapper → Entity/DB`，返回值由 Service 组装成 VO。

### 通用包 `common`

| 子包 | 职责 |
| --- | --- |
| `config` | Sa-Token 拦截器、MyBatis-Plus、WebMvc、dotenv 等配置 |
| `exception` | `BizException`、`GlobalExceptionHandler`（统一转 `Result`） |
| `result` | `Result<T>`、`PageResult<T>`、`ResultCode` 错误码 |
| `filter` | `TraceIdFilter`（MDC 链路 ID） |
| `cache` | `CacheEvictor`、`DictionaryCache`、`JsonCache` |
| `storage` | `FileStorageService`（MinIO / 本地两套实现） |
| `util` | `PhoneCrypto`、`ContentCipher`、`RedisKeys`、`TxHooks` |
| `discovery` | 局域网发现（App 自动找服务器地址） |

---

## 3. 模块依赖（12 个业务模块）

```mermaid
flowchart TB
    subgraph 交易核心
        item[item 商品]
        order[order 面交单]
        cart[cart 购物车/收藏]
        chat[chat 私聊]
    end
    subgraph 支撑
        user[user 用户/认证]
        school[school 学校校区]
        map[map 地图点位]
        search[search 搜索]
        file[file 文件]
        admin[admin 运营后台]
        platform[platform 平台严选]
        system[system 系统]
    end

    item --> user
    item --> school
    item --> search
    item --> admin
    order --> item
    order --> user
    order --> chat
    cart --> item
    chat --> user
    chat --> item
    admin --> item
    admin --> user
    platform --> item
    platform --> user
    search --> item
```

> 单体允许模块间直接 `@Autowired` 调用 Service（例如 `ItemService` 直接注入 `UserService`、`OrderService`）。**这正是拆分微服务后要改成 Feign/事件的地方**。

---

## 4. 核心数据模型（ER 图）

> 完整 DDL 见 [`sql/schema.sql`](../sql/schema.sql) 与 `sql/migration-*.sql`。下面只列主键/外键/核心字段。

```mermaid
erDiagram
    school ||--o{ campus : contains
    campus ||--o{ sys_user : belongs
    sys_user ||--o{ item : publishes
    item_category ||--o{ item : classifies
    sys_user ||--o{ meetup_order : buys
    sys_user ||--o{ meetup_order : sells
    item ||--o{ meetup_order : traded_in
    sys_user ||--o{ cart_item : owns
    item ||--o{ cart_item : in
    sys_user ||--o{ favorite : owns
    item ||--o{ favorite : favorited
    sys_user ||--o{ chat_session : buyer_side
    sys_user ||--o{ chat_session : seller_side
    item ||--o{ chat_session : about
    chat_session ||--o{ chat_message : contains
    item ||--o{ item_review : reviewed
    sys_user ||--o{ user_block : blocks
    sys_user ||--o{ credit_log : credit_changes
    sys_user ||--o{ sys_audit_log : operates

    sys_user {
        bigint id PK
        varchar phone "AES 加密存储"
        varchar phone_hash "SHA-256，唯一索引"
        varchar nickname
        varchar avatar
        bigint school_id FK
        bigint campus_id FK
        int status "1正常 3封禁"
        int role_flags "8=运营"
        int credit_score
        int publish_limit
        int chat_limit
        varchar device_id
        datetime created_at
        datetime last_login_at
    }
    school {
        bigint id PK
        varchar name
    }
    campus {
        bigint id PK
        bigint school_id FK
        varchar name
    }
    item {
        bigint id PK
        bigint seller_id FK
        bigint category_id FK
        varchar title
        varchar description
        decimal price
        decimal original_price
        int condition_level
        int stock
        int status "1审核中 2在售 4已售出 5已下架 6审核未通过 7已过期 8违规下架"
        int seller_type "1个人 2平台"
        varchar images "JSON 数组"
        varchar cover_url
        varchar video_url
        int view_count
        int fav_count
        int chat_count
        int sale_count
        datetime expire_at
        datetime published_at
    }
    item_category {
        bigint id PK
        varchar name
        bigint parent_id
        int sort
    }
    meetup_order {
        bigint id PK
        varchar order_no
        varchar batch_no
        bigint buyer_id FK
        bigint seller_id FK
        bigint item_id FK
        varchar item_title
        int quantity
        decimal deal_price
        bigint point_id
        varchar point_snapshot
        datetime time_slot_start
        datetime time_slot_end
        int status "1待确认 2待面交 3完成 4拒绝 5取消"
        varchar verify_code
        datetime verify_expire_at
        datetime buyer_confirm_at
        datetime seller_confirm_at
        int reschedule_count
    }
    cart_item {
        bigint id PK
        bigint user_id FK
        bigint item_id FK
        int quantity
    }
    favorite {
        bigint id PK
        bigint user_id FK
        bigint item_id FK
    }
    chat_session {
        bigint id PK
        bigint buyer_id FK
        bigint seller_id FK
        bigint item_id FK
        bigint last_msg_id
        varchar last_msg_text
        datetime last_msg_at
        int risk_level
        int buyer_deleted
        int seller_deleted
    }
    chat_message {
        bigint id PK
        bigint session_id FK
        bigint sender_id
        bigint receiver_id
        int msg_type "文本/图片"
        text content "文本 AES-GCM 加密"
        int risk_level
        int recalled
        datetime created_at
    }
    item_review {
        bigint id PK
        bigint item_id FK
        int review_type
        int status
        bigint operator_id
        varchar reason
    }
    trade_point {
        bigint id PK
        bigint campus_id
        varchar name
        decimal latitude
        decimal longitude
        int status
    }
```

### 平台严选相关表（独立子域）

| 表 | 说明 |
| --- | --- |
| `platform_consign_point` | 平台代卖点/寄卖点 |
| `platform_coupon` | 优惠券模板 |
| `platform_coupon_user` | 用户已领券 |
| `platform_flash_sale` | 秒杀活动 |
| `platform_flash_sale_item` | 秒杀活动商品 |
| `platform_seckill_record` | 抢购记录 |
| `platform_item_row` | 平台商品视图/行对象（非实体表） |

---

## 5. 核心类图（以 item 模块为例，其余模块同构）

```mermaid
classDiagram
    class ItemController {
        +list(ItemQueryDTO) Result~PageResult~ItemVO~~
        +detail(Long id) Result~ItemVO~
        +publish(ItemPublishDTO, Long userId) Result~Map~
        +update(Long id, ItemUpdateDTO) Result~Map~
        +offline(Long id) Result~Void~
        +mine() Result~PageResult~ItemVO~~
    }
    class ItemService {
        +query(ItemQueryDTO) PageResult~ItemVO~
        +detail(Long id) ItemVO
        +publish(ItemPublishDTO, Long) Map
        +update(Long, ItemUpdateDTO, Long) Map
        +offline(Long, Long) void
        +statusText(Integer) String$
    }
    class ItemMapper {
        +lockStock(Long itemId, int qty) int
        +incrSaleCount(Long itemId, int qty) int
        +releaseLock(Long itemId) int
    }
    class Item {
        +Long id
        +Long sellerId
        +String title
        +BigDecimal price
        +Integer status
    }
    class ItemPublishDTO
    class ItemUpdateDTO
    class ItemQueryDTO
    class ItemVO

    ItemController --> ItemService
    ItemService --> ItemMapper
    ItemMapper --> Item
    ItemController ..> ItemPublishDTO
    ItemController ..> ItemUpdateDTO
    ItemController ..> ItemQueryDTO
    ItemController ..> ItemVO
```

关键点：
- `ItemService.detail` 走 **Redis 缓存**（`CacheEvictor` 在变更后清缓存），`query` 走 **ES 搜索**（索引由 `ItemIndexService` 在事务提交后同步）。
- `ItemMapper.lockStock` 用**单条条件 UPDATE** 扣库存，防超卖。

---

## 6. 关键时序图

### 6.1 手机号验证码登录

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant AC as AuthController
    participant US as UserService
    participant R as Redis
    participant DB as MySQL
    participant ST as Sa-Token

    C->>AC: POST /api/v1/auth/sms-code {phone}
    AC->>US: sendSmsCode(phone)
    US->>R: SET ershou:sms:code:{phone}=code EX 300
    US-->>C: Result.ok()

    C->>AC: POST /api/v1/auth/login {phone, code}
    AC->>US: login(dto)
    US->>R: GET 验证码并比对
    alt 验证码错误/过期
        US-->>C: code=20001 验证码错误
    end
    US->>R: DEL 验证码（一次性）
    US->>DB: SELECT sys_user WHERE phone_hash = SHA256(phone)
    alt 用户不存在
        US->>DB: INSERT 新用户（轻注册）
    else 已存在且 status=3
        US-->>C: code=20002 账号封禁
    end
    US->>ST: StpUtil.login(userId) → 写 Redis db0
    US-->>C: {token, user}
```

### 6.2 发布商品（机审已移除后的当前逻辑）

```mermaid
sequenceDiagram
    autonumber
    participant C as 客户端
    participant IC as ItemController
    participant IS as ItemService
    participant R as Redis
    participant DB as MySQL
    participant ES as Elasticsearch
    participant RV as ItemReviewService

    C->>IC: POST /api/v1/items
    IC->>IS: publish(dto, userId)
    IS->>R: 新号发布限额 INCR（注册 7 天内，默认 5/天）
    alt 超限
        IS-->>C: code=20003
    end
    IS->>DB: INSERT item(status=2 在售)
    IS->>RV: onItemLiveOptimistic(itemId) 登记上架审核页
    IS->>ES: 事务提交后 syncById（TxHooks.afterCommit）
    IS->>R: 清商品详情缓存
    IS-->>C: {itemId, status=2, statusText="在售"}
```

### 6.3 面交下单（状态流转）

```mermaid
sequenceDiagram
    autonumber
    participant B as 买家
    participant OC as OrderController
    participant OS as OrderService
    participant DB as MySQL
    participant CH as ChatService
    participant S as 卖家

    B->>OC: POST /api/v1/meetup-orders {itemId, quantity, pointId, timeSlot}
    OC->>OS: createSingle(buyerId, dto)
    OS->>DB: 校验商品存在/在售/库存/不能买自己的
    alt 校验失败
        OS-->>B: 30002/30004/30006 等
    end
    OS->>DB: INSERT meetup_order(status=1 待卖家确认)
    OS->>CH: 私聊通知卖家
    OS-->>B: order

    S->>OC: POST /api/v1/meetup-orders/{id}/accept
    OC->>OS: accept → status=2 待面交，生成核销码
    alt 非待确认状态
        OS-->>S: 40003
    end

    S->>OC: POST /api/v1/meetup-orders/{id}/confirm
    OC->>OS: confirm → status=3 已完成
    OS->>DB: 商品状态按剩余库存收口（售罄→已售出）
    OS->>DB: 信用分/销量等收尾
```

### 6.4 发送聊天消息

```mermaid
sequenceDiagram
    autonumber
    participant C as 发送方
    participant CC as ChatController
    participant CS as ChatService
    participant DB as MySQL
    participant P as ChatPushService
    participant R as 接收方

    C->>CC: POST /api/v1/chat/messages {sessionId, content}
    CC->>CS: send(...)
    CS->>DB: 校验会话成员/是否拉黑/每日条数限额
    CS->>CS: ContentCipher AES-GCM 加密正文
    CS->>DB: INSERT chat_message
    CS->>DB: 更新 chat_session 摘要/未读+1
    CS->>P: 本机直推 + 跨实例广播
    P-->>R: WebSocket 推送
    CS-->>C: ChatMessageVO
```

> 说明：机审模块已删除，发送消息不再做关键词拦截，`risk_level` 恒为 0。

---

## 7. 状态机

### 7.1 商品状态（item.status）

```mermaid
stateDiagram-v2
    [*] --> 审核中: 发布
    审核中 --> 在售: 审核通过/直接放行
    审核中 --> 审核未通过: 审核驳回
    在售 --> 已售出: 库存售罄
    在售 --> 已下架: 卖家下架
    已下架 --> 在售: 重新上架
    在售 --> 已过期: 到期
    已过期 --> 在售: 重新上架
    在售 --> 违规下架: 运营处置
    违规下架 --> 在售: 卖家申请+运营审核（可配置）
    已售出 --> [*]
```

| status | 含义 |
| --- | --- |
| 1 | 审核中 |
| 2 | 在售 |
| 4 | 已售出 |
| 5 | 已下架 |
| 6 | 审核未通过 |
| 7 | 已过期 |
| 8 | 违规下架 |

### 7.2 面交单状态（meetup_order.status）

```mermaid
stateDiagram-v2
    [*] --> 待卖家确认: 买家下单
    待卖家确认 --> 待面交: 卖家接受（生成核销码）
    待卖家确认 --> 已取消: 买家/卖家取消
    待卖家确认 --> 已拒绝: 卖家拒绝
    待面交 --> 已完成: 核销/确认收货
    待面交 --> 已拒绝: 面交失败
```

| status | 含义 |
| --- | --- |
| 1 | 待卖家确认 |
| 2 | 待面交 |
| 3 | 已完成 |
| 4 | 已拒绝 |
| 5 | 已取消 |

> `STATUS_ACTIVE = [1, 2]` 表示"进行中"，商品下架/售罄时会批量把进行中的单置为已取消。

---

## 8. 实现顺序建议（单体）

1. `common`：Result / PageResult / ResultCode / BizException / GlobalExceptionHandler / TraceIdFilter / SaTokenConfig。
2. `user` + `school`：登录、当前用户、学校校区。
3. `item` + `item_category`：列表、详情、发布、编辑、下架；接 Redis 缓存 + ES 索引。
4. `cart` + `favorite`。
5. `order`：面交单全流程 + 状态机。
6. `chat`：会话、消息、WebSocket 推送、拉黑、撤回。
7. `map`：点位、逆地理编码。
8. `admin`：审核、管理、上架审核。
9. `platform`：严选、代卖点、优惠券、秒杀。
10. `file`、`search`、`system` 收尾。
