# 全量微服务拆分约定（v1，2026-11-13）

> 目标：把旧单体 `ershou-server` 中剩余模块全部拆成独立微服务，网关 8088 全部路由到新服务，不再反代旧单体 8080。
> 旧单体代码保留只作参考，不再接收线上请求。

## 一、目标服务与端口

| 服务 | 端口 | 路由前缀 | 数据库 | 负责人 |
| --- | --- | --- | --- | --- |
| gateway | 8088 | `/api/v1/**`、`/ws/**` | — | Lead |
| user-service | 8081 | `/api/v1/auth/**`、`/api/v1/users/**`、`/api/v1/schools/**` | `ershou_user` | auth-member |
| item-service | 8082 | `/api/v1/items/**`、`/api/v1/categories/**`、`/api/v1/search/**` | `ershou_item` | item-member |
| order-service | 8083 | `/api/v1/cart/**`、`/api/v1/favorites/**`、`/api/v1/meetup-orders/**` | `ershou_order` | order-member |
| chat-service | 8084 | `/api/v1/chat/**`、`/ws/**` | `ershou_chat` | chat-member |
| map-service | 8085 | `/api/v1/map/**`、`/api/v1/trade-points/**` | `ershou_map` | map-member |
| platform-service | 8086 | `/api/v1/platform/**` | `ershou_platform` | platform-dev |
| admin-service | 8087 | `/api/v1/admin/**` | `ershou_admin` | admin-member |
| file-service | 8089 | `/api/v1/files/**` | 无（MinIO） | app-member |

## 二、通用约定

- Java 21 + Spring Boot 3.3.5 + Spring Cloud 2023.0.3 / SCA 2023.0.3.2；包名 `com.ershou.cloud.<服务名>`。
- 本地 `application.yml` 只保留端口/服务名/Nacos 地址/`spring.config.import`；数据源、Redis、Sa-Token、MyBatis-Plus、MQ、Seata、业务配置全部放 Nacos（dataId = `ershou-<服务名>.yaml`）。
- 统一返回 `com.ershou.cloud.common.result.Result` / `PageResult`，错误码沿用旧单体 `ResultCode` 语义。
- 鉴权：网关 Sa-Token 统一校验后注入 `X-User-Id`（并剥离客户端伪造头），服务内用 `UserContext.getUserId()` 读取；服务不自行解析 token（chat-service 的 WebSocket 握手除外，需复制旧单体 chat 的 token 校验逻辑）。
- admin-service 额外校验管理员：调 user-service 内部接口确认 `isAdmin/roleFlags`；非管理员返回 403。
- 跨服务只读/写：通过 OpenFeign 调 `/inner/**` 接口，**禁止跨库 SQL**；Feign 需配 fallback 返回统一错误码。
- 写自己的库，不写别人的库；需要别的域数据时走 Feign。
- 旧单体代码可直接复制改包名/配置，但必须删掉/替换跨模块直接 Mapper 调用。
- 前端路径与响应 JSON 保持不变（Web/App 零改动）。
- 不要修改：`ershou-cloud/pom.xml`、`ershou-cloud-common/**`、`ershou-cloud-gateway/**`、其他成员服务、旧单体 `ershou-server/**`、前端。
- 构建：`mvn -f ershou-cloud/ershou-<服务>-service/pom.xml -DskipTests package`（parent 用 `../pom.xml`；common 已 install 到本地仓库）。
- 数据库：MySQL `127.0.0.1:3306`，root 密码见 `.env` 的 `MYSQL_ROOT_PASSWORD`；Redis `6380`、密码见 `REDIS_PASSWORD`；Nacos `8848` 已在运行。
- 可自行创建自己的数据库/表并执行数据迁移 SQL；SQL 落盘到 `ershou-cloud/sql/split-<服务>.sql`。
- 完成后向 Lead 回报：端点清单、表清单、Feign 依赖、内部 API、构建/启动结果、遗留问题。

## 三、内部接口契约（最小集）

### user-service
- `GET /inner/users/{id}` → data: `{id,nickname,avatar,creditScore,identityFlag,roleFlags,isAdmin,status,schoolName,campusName}`
- `GET /inner/users/batch?ids=1,2` → list 同上
- `GET /inner/users?keyword=&page=&pageSize=` → 管理端用户列表（含 phone/createdAt）
- `POST /inner/users/{id}/status` body `{status}` → 封禁/解封（管理员/内部调用）
- `GET /inner/schools`、`GET /inner/campuses?schoolId=`

### item-service
- `GET /inner/items/{id}`（已有）→ 商品详情（含 sellerId/title/price/coverUrl/status/stock/preferPointId 等）
- `GET /inner/items/batch?ids=1,2` → 列表
- `GET /inner/items/by-point?campusId=&pointId=&page=&pageSize=` → 点位商品列表
- `GET /inner/items/count-by-point?campusId=` → `[{pointId,count}]`
- `POST /inner/items/{id}/status?status=` → 管理端审核/强制下架
- `POST /inner/items/platform` → 平台服务创建平台商品（body 同发布 DTO）
- 保留 `PUT /inner/items/{id}/lock`

### order-service
- `GET /inner/orders/{id}` → 面交单详情（含 buyer/seller/item/status）
- `GET /inner/orders?userId=&status=&page=&pageSize=` → 管理端/首页用
- `POST /inner/orders/{id}/status?status=` → 管理端处置

### map-service
- `GET /inner/points/{id}` → `{id,name,guide,longitude,latitude,campusId}`
- `GET /inner/points?campusId=` → 列表

### platform-service
- `GET /inner/platform/points?campusId=` → 平台代卖点
- `GET /inner/platform/summary?campusId=` → 平台数据概览

### chat-service
- `GET /inner/chat/unread?userId=` → 未读数（供用户页/管理端）
- `GET /inner/chat/sessions?userId=&page=&pageSize=` → 会话列表（供管理端）

### file-service
- `POST /api/v1/files/upload`（multipart，同旧单体）→ `{url}`
- `GET /api/v1/files/**` 静态代理不需要（MinIO 直链）

## 四、数据归属

| 库 | 表 |
| --- | --- |
| ershou_user | sys_user、school、campus、user_block |
| ershou_item | item、item_category、item_review |
| ershou_order | cart_item、favorite、favorite_folder、meetup_order、credit_log、notification、review |
| ershou_chat | chat_session、chat_message |
| ershou_map | trade_point |
| ershou_platform | platform_*、consign_order |
| ershou_admin | sys_audit_log、report_ticket、ai_audit_record |

迁移方式：`CREATE TABLE <新库>.<表> LIKE ershou.<表>; INSERT INTO <新库>.<表> SELECT * FROM ershou.<表>;`，完成后旧 `ershou` 库停写。

## 五、验收标准

1. 单模块 `mvn package` 成功；
2. 服务能在指定端口启动并注册到 Nacos；
3. 直接访问（带 `X-User-Id`）或经网关访问核心接口返回 200/`code=0`；
4. 不出现跨库 SQL、不修改他人 scope；
5. 报告：端点/表/内部 API/构建与冒烟结果/已知问题。

## 六、admin 依赖的扩展内部 API（T107 追加，已批准）

### user-service
- `GET /inner/users`（管理列表，支持 keyword/page/pageSize）
- `GET /inner/users/stats` → `{total,banned,newToday}`
- `POST /inner/users/{id}/credit` body `{delta,reason}` → 信用分调整

### item-service
- `GET /inner/item-reviews?status=&page=&pageSize=` → 审核队列
- `GET /inner/item-reviews/{itemId}` → 单条审核详情
- `GET /inner/item-reviews/stats` → `{pending,todayApproved,todayRejected}`
- `POST /inner/item-reviews/{itemId}/reviewed` body `{action:approve|reject,remark}` 
- `POST /inner/item-reviews/{itemId}/offline` body `{reason}`
- `POST /inner/item-reviews/batch` body `{itemIds:[],action,remark}`
- `POST /inner/item-reviews/backfill`（重建审核队列表）
- `GET /inner/items/stats` → `{onSale,pendingReview,offline}`

### map-service
- `GET /inner/points?campusId=&status=`（管理列表）
- `GET /inner/points/stats` → `{total,pending,disabled}`
- `POST /inner/points/{id}/audit?action=approve|reject`
- `POST /inner/points/{id}/status?status=`
- `POST /inner/points/cleanup-empty`

### order-service
- `GET /inner/orders/stats` → `{total,today,completed}`

### admin-service 新增接口（已批准）
- `GET /api/v1/admin/ai-audit-records`（只读）
- `GET /api/v1/admin/reports`（举报工单列表）
- `POST /api/v1/admin/reports/{id}/handle`（处置）

### 归属约定
- `ai_audit_record` 归 `ershou_admin`（admin 库），item-service 不建/不写该表。
- `item_review` 归 `ershou_item`，admin 通过上述 inner API 读写。