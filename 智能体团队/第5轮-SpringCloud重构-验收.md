# 第 5 轮 · Spring Cloud 微服务重构（演示版）· 验收报告

> 验收时间：2026-10-19 ｜ 验收人：`lead` ｜ 契约：[`第5轮-SpringCloud重构-设计.md`](./第5轮-SpringCloud重构-设计.md)
> 结论：**通过** —— 5 个模块全部构建成功，Docker 四中间件健康，4 个微服务注册 Nacos，网关全链路 11/11 步 PASS（29 项断言），Seata AT 回滚 / RabbitMQ 异步 / Sentinel 限流 / Nacos 动态配置 / MySQL 分库 / Redis 隔离全部有实测证据。

---

## 0. 结果摘要

| 验收项 | 结果 | 关键证据 |
| --- | --- | --- |
| Docker 中间件（Nacos/RabbitMQ/Seata/Sentinel） | ✅ 4/4 Up（nacos/rabbitmq healthy） | `docker ps`、Nacos readiness 200、RabbitMQ API 200、Seata 8091/7091、Sentinel 8858 |
| Maven 构建（5 模块） | ✅ BUILD SUCCESS | `mvn -f ershou-cloud/pom.xml -DskipTests package` |
| 服务注册 | ✅ 4 个服务全部注册 | Nacos `DEFAULT_GROUP`：gateway/user/item/order |
| Nacos 配置中心 | ✅ 4 个 dataId 已发布 | `ershou-gateway/user/item/order.yaml` |
| MySQL 分库 | ✅ 三库 + 独立账号 + 223 行迁移 | user 190/1/3、item 17/10、order 2；旧库未被修改 |
| 网关全链路（经 8088） | ✅ **11/11 PASS、29/29 断言** | `scripts/cloud-demo.ps1` → `.build/cloud/demo-result.json`（exit 0） |
| Seata AT 回滚 | ✅ 回滚生效 | debugFail → `code=90001`；item 库存不变、订单不落库、两库 `undo_log` 0→0、日志 `Rollbacked` |
| RabbitMQ 异步解耦 | ✅ 消费成功 | 下单后 `item.sale_count` delta=1、队列 consumers=1、`[mq][order-created]` 日志 |
| Sentinel 限流 | ✅ 429 生效 | 并发 10 次 = 200×5 + **429×5**，body `{"code":429,...}`；Feign fallback 另测 90001 |
| Nacos 动态配置 | ✅ 热更新生效 | 改 `ershou.order.max-qty=99` → qty=100 返回 10001「单次最多购买 99 件」，无需重启 |
| Redis 数据隔离 | ✅ 各库前缀独立 | db0 仅 `Authorization:login:*`；db1 `ershou:user:*`；db2 `ershou:item:*`；db3 `ershou:order:*` |
| 前端接入 | ✅ 零代码改动 | vite 代理 env 化，`npm run dev -- --mode cloud` 指向 8088；`npm run build` exit 0 |
| 文档 | ✅ 5 份 | cloud README / architecture（4 mermaid）/ migration-roadmap / demo-script（26KB）/ docker README |

**额外亮点：会话互通** —— user-service（8081）登录拿到的 token，直接请求旧单体（8080）`/users/me` 返回 200；反向亦然；8081 logout 后两端同时 10002（共享 Redis db0 的 Sa-Token 会话）。

---

## 1. 交付清单

### 1.1 工程（`ershou-cloud/`，5 模块）
| 模块 | 端口 | 说明 |
| --- | --- | --- |
| `ershou-cloud-common` | — | Result/ResultCode/PageResult/BizException/全局异常/TraceIdFilter/UserContext/PhoneCrypto/Ids + AutoConfiguration |
| `ershou-cloud-gateway` | **8088** | Spring Cloud Gateway：Nacos 发现 + Sa-Token Reactor 鉴权（共享 db0）+ X-User-Id 注入 + Sentinel 限流 + 绞杀者路由（未迁移路径反代单体 8080）+ CORS + TraceId |
| `ershou-user-service` | 8081 | 认证/用户/学校校区；MySQL `ershou_user`、Redis db1、Sa-Token 会话 db0 |
| `ershou-item-service` | 8082 | 商品读/分类/详情缓存/内部库存锁定/AMQP 消费；MySQL `ershou_item`、Redis db2 |
| `ershou-order-service` | 8083 | 面交单创建/查询（OpenFeign→item、Seata AT）、MQ 生产；MySQL `ershou_order`、Redis db3 |

统计：Java 源文件约 90 个 + 5 个 pom + 各服务 application.yml；网关 8 类、user 12 类、item 18 类、order 18 类、common 12 类。

### 1.2 基础设施与脚本
- `docker/docker-compose.yml`（nacos 8848/9848、rabbitmq 5672/15672、seata 8091/7091、sentinel-dashboard 8858；`docker/seata/application.yml`；`docker/sentinel/Dockerfile` 自建镜像）
- `sql/init-cloud-databases.sql`（三库+独立账号+表+undo_log）、`sql/migrate-data.ps1`（快照迁移 223 行、可重复执行）
- `scripts/cloud-up.ps1`（compose→健康→发布 Nacos 配置→8 项冒烟，实测 8/8 PASS）、`cloud-down.ps1`、`cloud-publish-config.ps1`、`cloud-demo.ps1`（11 步 E2E）
- `docker/Dockerfile.service`（实测构建成功 `ershou/ershou-gateway:dev`）
- 文档：`ershou-cloud/README.md`、`docs/architecture.md`（4 mermaid）、`docs/migration-roadmap.md`、`docs/demo-script.md`、`docker/README.md`

### 1.3 版本矩阵（实测解析）
Spring Boot 3.3.5 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.2 → nacos-client 2.4.2、sentinel 1.8.8、**Seata client io.seata 1.8.0（父工程固定）**；Sa-Token 1.39.0（与单体同版）；MyBatis-Plus 3.5.9（分页需 `mybatis-plus-jsqlparser`）；Java 21。

---

## 2. 验收证据（逐条对应设计第七节）

### 2.1 Docker 中间件
```
ershou-nacos     nacos/nacos-server:v2.3.2    Up (healthy)  8848/9848
ershou-rabbitmq  rabbitmq:3-management        Up (healthy)  5672/15672
ershou-seata     seataio/seata-server:1.8.0   Up            8091/7091（Jib 镜像，配置挂载 application.yml）
ershou-sentinel  ershou/sentinel-dashboard:1.8.8 Up         8858（官方镜像 403，自建）
```
`GET /nacos/v1/console/health/readiness`=200；`GET :15672/api/overview`=200；`:8858/`=200；`:8091` LISTEN。
证据目录：`.build/cloud/logs/middleware-evidence-*.log`、`cloud-up-*.log/.json`。

### 2.2 构建
`mvn -f E:\ershou\ershou-cloud\pom.xml -DskipTests package` → **BUILD SUCCESS（5/5）**，产物：
gateway 55.5MB、user 49.8MB、item 84.5MB、order 94.5MB。

### 2.3 服务注册与配置
Nacos `DEFAULT_GROUP` 服务列表 = `ershou-gateway, ershou-user-service, ershou-item-service, ershou-order-service`；
配置中心 4 个 dataId：`ershou-gateway.yaml`、`ershou-user-service.yaml`、`ershou-item-service.yaml`、`ershou-order-service.yaml`。

### 2.4 分库与迁移
| 库 | 账号 | 表 | 行数 |
| --- | --- | --- | --- |
| `ershou_user` | `ershou_user` | sys_user / school / campus | 190 / 1 / 3 |
| `ershou_item` | `ershou_item` | item（含 `sale_count`）/ item_category / undo_log | 17 / 10 / 0 |
| `ershou_order` | `ershou_order` | meetup_order / undo_log | 2 / 0 |
迁移脚本两遍均可执行，`旧库合计 223 行 -> 新库合计 223 行`，旧库 `ershou` 未被修改（逐表前后行数对比）。

### 2.5 网关全链路（`scripts/cloud-demo.ps1`，13:42:40，22s，exit 0）
| 步骤 | 结果 | 证据 |
| --- | --- | --- |
| S1 发验证码 | PASS | `code=0` |
| S2 登录 | PASS | token 36 位、userId=1072 |
| S3 `/users/me` | PASS | id 与登录一致（网关 X-User-Id 透传） |
| S4 `/items` | PASS | records≥5（item-service） |
| S5 商品详情 | PASS | id 一致 |
| S6 创建面交单 | PASS | order #70；item18 库存 1→0；meetup_order 行存在 |
| S7 我的面交单 | PASS | 含 #70，itemTitle=小米台灯（Feign 跨服务拼装） |
| S8 Seata 回滚 | PASS | `code=90001`；item19 库存 1→1；无新订单；日志 `Rollbacked` |
| S9 RabbitMQ | PASS | `sale_count 0→1` delta=1；queue consumers=1；消费日志 |
| S10 Sentinel 限流 | PASS | 200×5 + **429×5** |
| S11 旧路径反代 | PASS | 经网关 `/platform/shop/points` → 200 `code=0`，返回 3 个代卖点 |
汇总：`{steps:11, pass:11, fail:0, checksTotal:29, checksFailed:0, exitCode:0}`；JSON 24.8KB。

### 2.6 Seata（回滚闭环，order-member 实测）
- 客户端日志：`register TM success / register RM success (client 1.8.0, server 1.8.0)`、`Auto proxy data source by AT mode`
- `debugFail=true`：响应 `90001`，日志 `Begin new global transaction → will be rollback → rollback status: Rollbacked`
- DB 前后：item889? 实际用例 item888 `stock 5→5`、`meetup_order 4→4`、两库 `undo_log 0→0`
- 正常单：`code=0`，`stock 5→4`、`sale_count +1`（MQ）、订单 +1、undo_log 归 0

### 2.7 RabbitMQ
exchange `ershou.order.topic`（topic, durable）；queue `ershou.item.order-created.queue`（durable, DLX `ershou.order.dlx`）；binding `order.created`；consumers=1；死信队列 `ershou.item.order-created.dlq` 存在。
消费幂等：同一 orderId 重发不重复累加（`ershou:item:mq:order:{orderId}` + `[dup]` 日志）。

### 2.8 Sentinel
- 网关：`GET /api/v1/items` QPS=5 → 并发 10 次返回 200×5 + 429×5，body `{"code":429,"message":"请求过于频繁，请稍后重试"}`
- Feign fallback：item-service 停止时下单返回 `{"code":90001,"message":"商品服务暂不可用"}`（Sentinel fallback 生效）
- Dashboard `:8858` 可访问

### 2.9 Nacos 动态配置
order-service 用 `@RefreshScope` 读取 `ershou.order.max-qty`；Nacos 改为 99 后，qty=100 请求返回 10001「单次最多购买 99 件」，**无需重启**。

### 2.10 前端接入（零代码改动）
- `ershou-web/vite.config.ts`：`/api`、`/ws` 代理目标 = `process.env.VITE_PROXY_TARGET || .env.cloud || 127.0.0.1:8080`
- `ershou-web/.env.cloud`：`VITE_PROXY_TARGET=http://127.0.0.1:8088`
- `npm run build` exit 0；`npm run dev -- --mode cloud` 后页面请求走网关（登录/首页/详情/面交单接口均 200）
- App 只需在连接页填 `host:8088`

### 2.11 Redis 隔离
| DB | 抽样 key | 结论 |
| --- | --- | --- |
| 0 | `Authorization:login:token:*`（222 个）+ 21 个旧单体遗留 `ershou:*` | 云服务**未写入**任何业务 key（仅 Sa-Token 会话）；`ershou:*` 为旧单体 8080 在绞杀者过渡期的历史数据，不在本轮清理范围 |
| 1 | `ershou:user:sms:limit:*` | user-service 独占（纯前缀） |
| 2 | `ershou:item:detail:*` / `ershou:item:view:*` / `ershou:item:mq:order:*` | item-service 独占（纯前缀） |
| 3 | （暂无 key，order-service 幂等键按需写入） | order-service 独占 |
证据：`docker exec ershou-redis redis-cli -n <db> --scan`；完整采集见 `.build/cloud/logs/final-infra-acceptance.md` §4（云服务侧 db1/db2/db3 前缀隔离 100% 达标；db0 仅"云服务不写业务 key"口径成立，旧单体遗留 key 已如实标注）。

---

## 3. 关键问题与解决（踩坑记录）

1. **Seata 版本错配（最关键）**：SCA 2023.0.3.2 的 `spring-cloud-starter-alibaba-seata` 默认带 **Apache Seata 2.1.0（org.apache.seata）**，与已有 Docker 镜像 **io.seata 1.8.0** 大版本/发行方都不同 → 客户端连不上 TC。修复：父工程统一管理 `io.seata:seata-spring-boot-starter:1.8.0`，item/order 对 SCA starter 排除 `org.apache.seata` 并显式依赖 1.8.0；日志确认 `client 1.8.0 / server 1.8.0`。
2. **Docker Desktop 容器 IP 宿主机不可达**：Seata 容器向 Nacos 注册 `172.20.0.5:8091`，Windows 宿主机连不通（`127.0.0.1:8091` 可达）。修复（演示级，纯配置）：客户端 `seata.registry.type=file` + `seata.service.grouplist.default=127.0.0.1:8091`（compose 已映射端口），已实测 TM/RM 注册成功。
   **可选改进（本轮未启用，供后续完善；结论已实测修正）**：该 1.8.0 Jib 镜像的 `ContainerHelper.getHost()` 支持环境变量 `SEATA_IP`，但 **`SEATA_IP=127.0.0.1` 会被 `NetUtil.isValidIp(host,false)` 回环校验拒绝而回退容器 IP**，必须填**宿主可达的非回环 IP**（如 WLAN IP；隔离实验中 `SEATA_IP=192.168.65.254` 实测生效、宿主 8091 可达）。完整切换路径（compose 加 `SEATA_IP=<宿主IP>` 重启 → 客户端 `registry.type` 改回 `nacos` → 重发 dataId → 重启 item/order）见 `ershou-cloud/docker/README.md` §5.3。
3. **MyBatis-Plus 3.5.9 分页插件拆包**：分页失效（`pageSize=10` 返回全表、`total=0`）→ 需要 `mybatis-plus-jsqlparser` 依赖 + `MybatisPlusInterceptor`。
4. **Sa-Token 双 SaTokenDao**：`sa-token-redis-jackson` 自动配置会用业务 db1 再注册一个 Dao → user-service 会话 dao 标 `@Primary`（db0）并注释说明。
5. **Sentinel 429 JSON**：SCA 的 `BlockExceptionHandler` 与自定义 bean 冲突 → 网关用最高优先级 `WebExceptionHandler` 输出 429 JSON。
6. **PowerShell 脚本编码**：UTF-8 无 BOM 的中文脚本在 PS 5.1 下解析失败；`mysql -e` 双引号被吞 → 脚本统一 UTF-8 BOM、mysql 走 stdin/长参数。
7. **迁移脚本汇总显示 0**：`migrate-data.ps1` 末尾"新库表行数"统计段连错库（逐表校验与独立账号复核 223 行正确）→ 已在 README FAQ 给出复核 SQL；修复留待下轮。

---

## 4. 已知限制与后续路线

1. **只迁移了核心切片**（user/item 读/order），其余模块（chat/admin/map/platform/search/cart/favorites）仍由网关反代旧单体 8080 —— 绞杀者过渡，功能不中断；迁移路线见 `ershou-cloud/docs/migration-roadmap.md`（5 阶段）。
2. **数据一致性是快照式**：`migrate-data.ps1` 一次性把旧库数据灌入新库，之后新服务写入不回写旧库；生产需双写/CDC + 对账（roadmap 已写）。
3. **Seata 客户端走 grouplist 直连**（Docker Desktop 限制），`registry=nacos` 为可选改进。
4. **Nacos 注册 IP 为网卡 IP**（如 `26.236.162.177`），换网络环境后服务间调用需注意；演示用可配 `spring.cloud.nacos.discovery.ip`。
5. **未做**：链路追踪（Sleuth/SkyWalking）、分布式锁、网关灰度、K8s；属生产化后续。
6. 测试/演示数据：多次 E2E 消耗了库存与订单；正式演示前重跑 `ershou-cloud/sql/migrate-data.ps1` 可恢复快照基线（本轮最后已重置：16 在售 / 2 单 / 190 用户）。

---

## 5. 复现步骤（演示用）

```powershell
cd E:\ershou

# 1) 起中间件（Nacos/RabbitMQ/Seata/Sentinel 四容器）+ 发布 Nacos 配置 + 冒烟
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1

# 2) 建库 + 迁移（幂等；已建可跳过）
Get-Content .\ershou-cloud\sql\init-cloud-databases.sql -Raw | mysql --host=127.0.0.1 --user=root --password=__REDACTED_DB_PASSWORD__ --default-character-set=utf8mb4
powershell -ExecutionPolicy Bypass -File .\ershou-cloud\sql\migrate-data.ps1
# ⚠️ 注意：migrate-data.ps1 会 TRUNCATE 重灌分库（恢复 190 用户/17 商品/2 面交单基线），
#    联调或演示期间**不要重跑**（会清掉演示产生的订单与库存变化）；只想核对一致性用参数 -DryRun。
#    本轮最后一次重置时间：2026-10-19 13:43:19（之后 E2E 数据已回到基线）。

# 3) 起 4 个微服务（各自一个终端；jar 已构建）
java -jar .\ershou-cloud\ershou-user-service\target\ershou-user-service-1.0.0.jar
java -jar .\ershou-cloud\ershou-item-service\target\ershou-item-service-1.0.0.jar
java -jar .\ershou-cloud\ershou-order-service\target\ershou-order-service-1.0.0.jar
java -jar .\ershou-cloud\ershou-cloud-gateway\target\ershou-cloud-gateway-1.0.0.jar

# 4) 起旧单体（未迁移路径反代目标；本项目当前 8080 已在跑）
java -jar .\ershou-server\target\ershou-server-1.0.0.jar

# 5) 一键端到端演示（11 步断言）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1

# 6)（可选）前端切到网关
cd ershou-web; npm run dev -- --mode cloud
```

---

## 6. 本轮团队分工（T24–T30）

| 任务 | 成员 | 交付 | 状态 |
| --- | --- | --- | --- |
| T24 | platform-dev | parent + common + Gateway（8 类）+ Nacos 配置 4 个 + 版本对齐 | ✅ |
| T25 | admin-member | user-service（认证/用户/学校，会话 db0 互通） | ✅ |
| T26 | item-member | item-service（商品读/缓存/内部锁/MQ 消费/分页修复） | ✅ |
| T27 | order-member | order-service（Feign/Seata AT/MQ 生产/动态配置） | ✅ |
| T28 | chat-member | Docker 编排验证 + 建库/迁移 SQL + cloud-up/down + Dockerfile 实构建 + 中间件证据 | ✅ |
| T29 | map-member | 前端接网关（env 化）+ 演示脚本（11 步全绿）+ demo-script 文档 | ✅ |
| T30 | app-member | cloud README + architecture（4 mermaid）+ migration-roadmap | ✅ |
| 基础设施/整合/验收 | lead | 接手 compose/seata/sentinel/发布与启动、Seata 版本裁决、E2E 复核、数据重置、文档收口 | ✅ |

> 本轮共 5 个模块、约 90 个 Java 源文件、4 个中间件容器、3 个分库、4 个服务实例、11 步端到端断言，全部有可复现证据（`.build/cloud/`）。
