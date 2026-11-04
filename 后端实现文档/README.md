# 后端实现文档（单体 + 微服务）

> 目的：照着这份文档，可以从零把后端重新实现一遍。包含 **UML**、**接口文档**、**数据模型**、**关键业务规则**。
>
> 分两套：
> - **单体（拆分前）**：`ershou-server`，12 个业务模块，`/api/v1/**` 共 **121 个接口**，端口 `8080`。
> - **微服务（拆分后）**：`ershou-cloud`，网关 `8088` + user/item/order 三个服务 + 旧单体反代（绞杀者模式）。

---

## 一、文档地图

| 文件 | 内容 |
| --- | --- |
| [README.md](README.md)（本文） | 通用约定：返回结构、错误码、鉴权、分页、技术栈 |
| [01-单体-架构与UML.md](01-单体-架构与UML.md) | 单体系统架构图、分层结构、模块依赖、ER 图、核心类图、登录/发布/下单/聊天时序图、状态机 |
| [02-单体-接口-用户与学校.md](02-单体-接口-用户与学校.md) | auth / users / schools |
| [03-单体-接口-商品与搜索与文件.md](03-单体-接口-商品与搜索与文件.md) | items / categories / search / files |
| [04-单体-接口-订单购物车收藏.md](04-单体-接口-订单购物车收藏.md) | meetup-orders / cart / favorites |
| [05-单体-接口-聊天.md](05-单体-接口-聊天.md) | chat（含 WebSocket） |
| [06-单体-接口-地图与点位.md](06-单体-接口-地图与点位.md) | map / trade-points |
| [07-单体-接口-运营后台.md](07-单体-接口-运营后台.md) | admin（审核 / 管理 / 上架审核） |
| [08-单体-接口-平台严选.md](08-单体-接口-平台严选.md) | platform（shop / me / admin / console） |
| [09-微服务-架构与UML.md](09-微服务-架构与UML.md) | 服务架构、网关路由表、数据隔离、下单 Seata 时序、MQ 时序、登录鉴权时序 |
| [10-微服务-接口文档.md](10-微服务-接口文档.md) | 网关路由 + user/item/order 对外接口 + `/inner/**` 内部接口 |

> 数据库 DDL：单体 [`sql/schema.sql`](../sql/schema.sql) + [`sql/migration-*.sql`](../sql/)；微服务 [`ershou-cloud/sql/init-cloud-databases.sql`](../ershou-cloud/sql/init-cloud-databases.sql)。

---

## 二、统一返回结构

所有接口都返回 `Result<T>`（[Result.java](../ershou-server/src/main/java/com/ershou/campus/common/result/Result.java)）：

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "traceId": "链路追踪 ID"
}
```

- `code = 0` 表示成功，非 0 表示业务错误（**HTTP 状态码通常仍是 200**，错误靠 `code` 判断）。
- `data` 是具体业务数据，无返回值时为 `null`。

分页接口的 `data` 是 `PageResult<T>`（[PageResult.java](../ershou-server/src/main/java/com/ershou/campus/common/result/PageResult.java)）：

```json
{
  "total": 100,
  "pageNum": 1,
  "pageSize": 10,
  "records": [ ]
}
```

> 注意：单体 `PageResult` 的字段是 `records`（不是 `list`）；微服务里的 `PageResult` 见第 10 篇，字段可能一致，以源码为准。

---

## 三、错误码表

来自 [ResultCode.java](../ershou-server/src/main/java/com/ershou/campus/common/result/ResultCode.java)，**实现时直接抄这张表**：

| 码段 | 枚举 | code | 含义 |
| --- | --- | --- | --- |
| 通用 | SUCCESS | 0 | ok |
| 通用 | PARAM_ERROR | 10001 | 参数校验失败 |
| 通用 | NOT_LOGIN | 10002 | 未登录或登录已过期 |
| 通用 | NO_PERMISSION | 10003 | 无权限访问 |
| 通用 | RATE_LIMIT | 10004 | 操作过于频繁 |
| 用户 | SMS_CODE_ERROR | 20001 | 验证码错误或已过期 |
| 用户 | USER_BANNED | 20002 | 账号已被限制 |
| 用户 | PUBLISH_LIMIT | 20003 | 今日发布已达上限 |
| 用户 | CREDIT_LOW | 20004 | 信用分不足 |
| 商品 | ITEM_NOT_FOUND | 30001 | 商品不存在或已删除 |
| 商品 | ITEM_SOLD | 30002 | 商品已售出 |
| 商品 | ITEM_AUDITING | 30003 | 商品正在审核中 |
| 商品 | ITEM_LOCKED | 30004 | 商品刚被他人锁定 |
| 商品 | ITEM_NOT_OWNER | 30005 | 只能操作自己的商品 |
| 商品 | ITEM_STATUS_ERROR | 30006 | 当前商品状态不允许该操作 |
| 订单 | ORDER_NOT_FOUND | 40001 | 面交单不存在 |
| 订单 | ORDER_NO_PERMISSION | 40002 | 无权操作该面交单 |
| 订单 | ORDER_STATUS_ERROR | 40003 | 当前状态不允许此操作 |
| 订单 | VERIFY_CODE_ERROR | 40004 | 核销码错误或已过期 |
| 订单 | TIME_SLOT_INVALID | 40005 | 时间窗不合法（未来 7 天内、单次≤3 小时） |
| 内容 | CONTENT_VIOLATION | 50001 | 内容涉嫌违规（**注：机审已移除，此码保留**） |
| 内容 | RISK_BLOCKED | 50002 | 触发风控拦截 |
| 运营 | ADMIN_ITEM_STATUS | 60001 | 商品状态不允许该审核操作 |
| 运营 | ADMIN_POINT_STATUS | 60002 | 点位状态不允许该审核操作 |
| 运营 | ADMIN_REASON_REQUIRED | 60003 | 请填写驳回原因 |
| 运营 | ADMIN_SELF_OPERATION | 60004 | 不能对自己执行该操作 |
| 运营 | REVIEW_ALREADY_HANDLED | 61001 | 该商品已被处置 |
| 运营 | REVIEW_NOT_ACTIONABLE | 61002 | 商品已不在可处置状态 |
| 聊天 | CHAT_SELF | 70001 | 不能和自己私聊 |
| 聊天 | CHAT_NOT_FOUND | 70002 | 会话不存在或无权访问 |
| 聊天 | CHAT_LIMIT | 70003 | 今日私聊条数已达上限 |
| 聊天 | CHAT_CONTENT_INVALID | 70004 | 消息内容不合法 |
| 聊天 | CHAT_SEND_FAILED | 70005 | 消息发送失败 |
| 聊天 | CHAT_BLOCKED | 70006 | 已拉黑无法私聊 |
| 聊天 | CHAT_RECALL_DENIED | 70007 | 只能撤回自己 2 分钟内的消息 |
| 系统 | SYSTEM_ERROR | 90001 | 系统开小差 |
| 系统 | STORAGE_ERROR | 90003 | 文件存储不可用 |

---

## 四、鉴权约定（Sa-Token）

- 登录后服务端返回 `token`，后续请求带请求头：`Authorization: Bearer <token>`。
- 单体用 `SaInterceptor` 拦截 `/api/**`，白名单在 [SaTokenConfig.java](../ershou-server/src/main/java/com/ershou/campus/common/config/SaTokenConfig.java)（auth / categories / schools / map / trade-points / platform/shop 公开读 / search/status / 商品读 / 用户主页）。
- 会话存 Redis db0（`sa-token-redis-jackson`），`timeout=315360000`（10 年）、`active-timeout=-1`、多端并发、每次登录发新 token。
- 微服务由网关 `SaReactorFilter` 统一鉴权（见第 9 篇）。

---

## 五、分页与常用参数约定

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `pageNum` | long | 1 | 页码，从 1 开始 |
| `pageSize` | long | 10 或 20（各接口不同） | 每页条数 |

排序、筛选参数各接口单独说明。

---

## 六、技术栈

| 层 | 组件 | 版本 |
| --- | --- | --- |
| 语言 | Java | 21 |
| 单体框架 | Spring Boot | 3.3.5 |
| 微服务 | Spring Cloud / Alibaba | 2023.0.3 / 2023.0.3.2 |
| ORM | MyBatis-Plus | 3.5.9 |
| 鉴权 | Sa-Token | 1.39.0 |
| 数据库 | MySQL | 8.0 |
| 缓存/会话 | Redis | 7（6380） |
| 搜索 | Elasticsearch | 8.13.4 |
| 对象存储 | MinIO | RELEASE 2025-09-07 |
| 消息 | RabbitMQ | 3-management |
| 注册/配置 | Nacos | 2.3.2 |
| 分布式事务 | Seata | server 1.8.0 / client 2.1.0 |
| 限流降级 | Sentinel | 1.8.8 |
| 地图 | 高德 Web API | AMAP_WEB_KEY |

---

## 七、如何"自己实现一遍"的建议顺序

1. 先按 [01-单体-架构与UML.md](01-单体-架构与UML.md) 搭单体骨架（common + 14 个 module 的六层结构）。
2. 按 [02](02-单体-接口-用户与学校.md) → [03](03-单体-接口-商品与搜索与文件.md) → [04](04-单体-接口-订单购物车收藏.md) → [05](05-单体-接口-聊天.md) → [06](06-单体-接口-地图与点位.md) → [07](07-单体-接口-运营后台.md) → [08](08-单体-接口-平台严选.md) 依次实现接口与业务规则。
3. 单体跑通后，再按 [09-微服务-架构与UML.md](09-微服务-架构与UML.md) 拆出 user/item/order 三个服务，网关按路由表接入，未迁移路径反代回单体。
4. 对照 [10-微服务-接口文档.md](10-微服务-接口文档.md) 实现三个新服务的对外接口和 `/inner/**` 内部接口。

---

> 文档基于 2026-10-30 的代码快照生成；接口以源码为准，若源码变更请同步更新本目录。
