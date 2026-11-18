# ershou-cloud · Spring Cloud 微服务演示工程

校园二手交易平台的微服务化切片（第 5 轮重构）：**Spring Cloud Gateway + user/item/order 三个服务 + 旧单体反代（绞杀者模式）**，配套 Nacos（注册+配置）、RabbitMQ（事件解耦）、Seata AT（分布式事务）、Sentinel（限流/降级）、MySQL 分库、Redis 分 db 隔离。

> 本轮定位是**可运行、可演示的技术切片**：已迁移的路径走新服务，未迁移的路径由网关原样反代回旧单体 :8080，前端/App 只需把 baseURL 端口从 8080 改成 8088，**零代码改动**。
> 端口：网关 **8088**｜user **8081**｜item **8082**｜order **8083**｜旧单体 **8080**。

**文档导航**

| 文档 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | 架构图（mermaid）、模块职责/端口/版本矩阵、登录与下单时序图、Redis 分 db / MySQL 分库说明 |
| [docs/migration-roadmap.md](docs/migration-roadmap.md) | 剩余模块（chat/admin/map/platform/search/cart…）分阶段迁移路线 + 旧库↔新库一致性策略 |
| [docs/demo-script.md](docs/demo-script.md) | 端到端演示脚本（**map-member 维护**）：一步步验证登录/商品/下单/Seata 回滚/MQ/限流/反代，以及 Web/App 接网关 |
| `智能体团队/第5轮-SpringCloud重构-设计.md` | 本轮唯一契约（架构、接口、中间件配置、验收标准） |

---

## 一、目录结构

```
ershou-cloud/
├── pom.xml                          # parent：Spring Boot 3.3.5 + Spring Cloud 2023.0.3 + SCA 2023.0.3.2
├── ershou-cloud-common/             # 公共包：Result/异常/TraceId/用户上下文/工具
├── ershou-cloud-gateway/            # :8088 网关：Sa-Token 鉴权、Sentinel 限流、路由/反代
├── ershou-user-service/             # :8081 认证/用户/学校校区（MySQL ershou_user + Redis db1）
├── ershou-item-service/             # :8082 商品读/内部库存锁定/MQ 消费（ershou_item + Redis db2）
├── ershou-order-service/            # :8083 面交单/Feign/Seata/MQ 生产（ershou_order + Redis db3）
├── docker/                          # docker-compose.yml（nacos/rabbitmq/seata/sentinel）+ seata/sentinel 配置
├── sql/                             # init-cloud-databases.sql（建库/账号/表）+ migrate-data.ps1（快照迁移）
├── nacos-configs/                   # 4 个 dataId 的配置模板（scripts/cloud-publish-config.ps1 发布）
└── docs/                            # architecture.md / migration-roadmap.md / demo-script.md
```

---

## 二、版本矩阵（本机已验证可构建/可运行）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| Java | **21**（本机 21.0.2） | 与旧单体一致 |
| Spring Boot | **3.3.5** | parent 继承 `spring-boot-starter-parent` |
| Spring Cloud | **2023.0.3** | Gateway / OpenFeign / LoadBalancer |
| Spring Cloud Alibaba | **2023.0.3.2** | BOM 带入 nacos-client **2.4.2** / sentinel **1.8.8** / seata-client **2.1.0** |
| Sa-Token | **1.39.0** | `sa-token-reactor-spring-boot3-starter` + `sa-token-redis-jackson`（与单体同版本、同 Redis db0、同 token-name → 会话互通） |
| MyBatis-Plus | **3.5.9** | 与旧单体一致 |
| MySQL | 8.0.43（本机 3306） | 旧库 `ershou` + 新库 `ershou_user/item/order` |
| Redis | redis:7-alpine 容器（**6380**） | 既有容器 `ershou-redis`，db0 会话共享 / db1/2/3 业务隔离 |
| RabbitMQ | `rabbitmq:3-management` | 容器 `ershou-rabbitmq`，5672 / 15672（guest/guest） |
| Nacos | `nacos/nacos-server:v2.3.2` | 容器 `ershou-nacos`，8848 / 9848（`NACOS_AUTH_ENABLE=false`，无鉴权） |
| Seata | Server 镜像 `seataio/seata-server:1.8.0` | 容器 `ershou-seata`，TC 8091 / 控制台 7091（seata/seata）；**client 由 SCA BOM 带入 2.1.0**，联调需确认版本兼容（预案见 FAQ 6.2） |
| Sentinel | Dashboard **1.8.8**（本地 jar + temurin 自建镜像 `ershou/sentinel-dashboard:1.8.8`） | 容器 `ershou-sentinel`，8858（sentinel/sentinel） |
| Docker | 29.6.1（Docker Desktop） | compose 工程名 `ershou-cloud` |
| Maven | 3.9.9 | 构建 5 个模块 |
| MinIO（既有） | RELEASE 2025-09-07 | 容器 `ershou-minio`，9010/9011（演示图片） |

---

## 三、前置条件

| 依赖 | 要求 | 检查命令（PowerShell，仓库根 `E:\ershou` 下执行） |
| --- | --- | --- |
| Windows + PowerShell | 5.1+（推荐 PowerShell 7） | `$PSVersionTable.PSVersion` |
| JDK | **21**（`JAVA_HOME` 指向 JDK21） | `java -version`（应为 21.x） |
| Maven | 3.9+ | `mvn -v` |
| Docker Desktop | 引擎已启动 | `docker version`、`docker info` |
| MySQL | 8.0（本机实测 8.0.43 在跑 3306） | `Get-NetTCPConnection -LocalPort 3306 -State Listen` |
| Redis | **6380**（沿用旧项目容器 `ershou-redis`） | `Get-NetTCPConnection -LocalPort 6380 -State Listen` |
| MinIO（可选） | 9010（演示图片，旧项目容器 `ershou-minio`） | `Get-NetTCPConnection -LocalPort 9010 -State Listen` |
| Node.js（可选） | 20+（跑 Web 前端演示） | `node -v` |

> 本机已有中间件容器（旧项目复用）：`ershou-redis`（6380）、`ershou-minio`（9010/9011）、`ershou-es`（9211，可选）。**不要重建/删除它们**；第 5 轮只新增 Nacos/RabbitMQ/Seata/Sentinel 四个容器。
> MySQL 账号/密码等本地配置在仓库根 `.env`（已 gitignore）；文中密码均为**演示值**，勿用于生产。

---

## 四、快速开始（推荐顺序）

> 以下命令均以仓库根 `E:\ershou` 为工作目录；脚本用 PowerShell 执行。
> 完整演示步骤（含前端/App 切网关）见 [docs/demo-script.md](docs/demo-script.md)，本文只给「把环境跑起来」的步骤。

### 4.1 启动中间件（Docker：Nacos/RabbitMQ/Seata/Sentinel）

```powershell
cd E:\ershou
# 一键脚本：compose up（含 sentinel 镜像 --build）→ 等 4 容器就绪 → 调 cloud-publish-config 发布 Nacos 配置 → 冒烟验收
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1
# 常用参数：
#   -DryRun        只校验计划（不启动）
#   -SkipBuild     镜像已最新时跳过 --build（省时间）
#   -SkipPublish   本次不发布 Nacos 配置
#   -SkipVerify    跳过冒烟验收
#   -HealthTimeoutSec 180  等待容器就绪超时
# 退出码：0=全部就绪；>0=失败项数；2=Docker 不可用
# 证据：.build\cloud\logs\cloud-up-<时间戳>.log / .json（结构化断言）

# 手工方式（等价）：
docker compose --env-file .env -f .\ershou-cloud\docker\docker-compose.yml up -d --build
docker compose --env-file .env -f .\ershou-cloud\docker\docker-compose.yml ps     # 等 4 个容器 running/healthy
```

| 容器 | 镜像 | 端口 | 访问/账号 |
| --- | --- | --- | --- |
| `ershou-nacos` | nacos/nacos-server:v2.3.2 | 8848 / 9848 | http://127.0.0.1:8848/nacos（`NACOS_AUTH_ENABLE=false`，无鉴权；控制台默认 nacos/nacos） |
| `ershou-rabbitmq` | rabbitmq:3-management | 5672 / 15672 | http://127.0.0.1:15672（guest/guest） |
| `ershou-seata` | seataio/seata-server:1.8.0 | 8091 / 7091 | http://127.0.0.1:7091（seata/seata） |
| `ershou-sentinel` | ershou/sentinel-dashboard:1.8.8（本地构建） | 8858 | http://127.0.0.1:8858（sentinel/sentinel） |

> 首次启动 Nacos 约 30–60s 才 healthy（`start_period=40s`）；Sentinel Dashboard 是本地 jar 自建镜像（DockerHub 官方镜像 403 不可用的预案）。Seata 配置文件挂载：`docker/seata/application.yml → /seata-server/resources/application.yml`（Jib 镜像只认 application.yml，没有 registry.conf/file.conf）。
> 中间件的更多细节/已知坑见 [docker/README.md](docker/README.md)（含 Seata 容器 IP 注册问题、Sentinel 自建镜像、健康检查命令）。

### 4.2 初始化分库 + 快照迁移

> 这一步**不在** `cloud-up.ps1` 里（Docker 脚本不碰 MySQL）；首次演示/换数据后需手工执行一次，可重复。

```powershell
# 1) 建库/建账号/建表（幂等；密码从 .env 的 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD 读取）
powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\init-cloud-databases.sql

# 2) 快照迁移（只读旧库 ershou → 新库；保留 id；可重复执行）
powershell -ExecutionPolicy Bypass -File .\ershou-cloud\sql\migrate-data.ps1 -DryRun   # 先看计划
powershell -ExecutionPolicy Bypass -File .\ershou-cloud\sql\migrate-data.ps1            # 真跑
# 期望：逐表「旧库行数 -> 新库行数 [OK]」，汇总「旧库合计 223 行 -> 新库合计 223 行」
# 日志：.build\cloud\logs\migrate-data-<时间戳>.log
```

当前实测基线（2026-10-19）：`sys_user=190`、`school=1`、`campus=3`、`item=17`、`item_category=10`、`meetup_order=2`，合计 **223 行**，旧库未被修改。
用独立账号复核（比迁移脚本汇总段更可靠，原因见 FAQ 6.6）：
```powershell
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_user  --password=<密码> -D ershou_user  -e "SELECT COUNT(*) FROM sys_user; SELECT COUNT(*) FROM school; SELECT COUNT(*) FROM campus;"
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item  --password=<密码> -D ershou_item  -e "SELECT COUNT(*) FROM item; SELECT COUNT(*) FROM item_category;"
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_order --password=<密码> -D ershou_order -e "SELECT COUNT(*) FROM meetup_order;"
```

> 说明：迁移是**一次性快照**——迁移后新服务写新库、旧单体写旧库，两者不自动同步（演示边界，详见 [migration-roadmap.md §4](docs/migration-roadmap.md)）。演示前重跑一次迁移即可让新库与旧库对齐。

### 4.3 发布 Nacos 配置（4 个 dataId，必须做）

服务本地 `application.yml` 只留端口/服务名/Nacos 地址，数据源/Redis/RabbitMQ/Seata 全部从 Nacos 拉取。**不发布配置，服务起不来**。

> `cloud-up.ps1` 默认已调用本步（所以正常流程不用再手动跑）；只有改了 `nacos-configs/*.yaml` 或 `-SkipPublish` 启动过 时，才需要单独重发。

```powershell
cd E:\ershou
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-publish-config.ps1
# 输出应为：发布完成：成功 4 / 失败 0；并打印 Nacos 配置列表
# 可选参数：-NacosAddr http://127.0.0.1:8848 -Group DEFAULT_GROUP -ConfigDir .\ershou-cloud\nacos-configs
```

校验（Nacos 无鉴权，直接 OpenAPI）：
```powershell
$nacos = 'http://127.0.0.1:8848/nacos'
$cfg = Invoke-RestMethod "$nacos/v1/cs/configs?search=accurate&dataId=&group=DEFAULT_GROUP&pageNo=1&pageSize=20"
$cfg.totalCount                       # 期望 4
$cfg.pageItems | Select-Object dataId, group
# 应为：ershou-gateway.yaml / ershou-user-service.yaml / ershou-item-service.yaml / ershou-order-service.yaml
```

> 配置内容：`ershou-gateway.yaml`（Sa-Token + Redis db0 + Sentinel dashboard/限流 QPS + 反代地址）、`ershou-user-service.yaml`（ershou_user + Redis db1）、`ershou-item-service.yaml`（ershou_item + Redis db2 + RabbitMQ + Seata）、`ershou-order-service.yaml`（ershou_order + Redis db3 + RabbitMQ + Seata + Feign/Sentinel + `ershou.order.max-qty`）。

### 4.4 构建（首次需要 install，让各服务能单独 spring-boot:run）

```powershell
cd E:\ershou
mvn -f .\ershou-cloud\pom.xml -DskipTests install
# 期望：5 个业务模块 + parent 全部 BUILD SUCCESS（reactor 共 6 项：parent 聚合 + common/gateway/user/item/order）
```
> `install` 会把 parent/common 装进本地 Maven 仓库；之后各模块可以独立 `mvn spring-boot:run`。
> 只想验证打包：`mvn -f .\ershou-cloud\pom.xml -DskipTests package`。

### 4.5 启动 4 个服务 + 旧单体（各开一个 PowerShell 窗口）

启动顺序：**中间件 → Nacos 配置 → user/item/order → gateway → 旧单体 8080**（旧单体也可提前启动；未迁移接口在单体就绪前返回 502，属预期）。

> ⚠️ 配置里的密码/密钥现在是 `${...}` 占位符：手工 `mvn spring-boot:run` 前必须先把仓库根 `.env` 载入当前 PowerShell 会话，否则会报 `Could not resolve placeholder 'MYSQL_PASSWORD'`。
> 推荐直接用 `scripts\start-full-cloud.ps1`（自动载入 `.env`、发布 Nacos 配置并启动全部服务）。

```powershell
cd E:\ershou

# ① user-service :8081
cd .\ershou-cloud\ershou-user-service
mvn spring-boot:run

# ② item-service :8082（新开窗口）
cd E:\ershou\ershou-cloud\ershou-item-service
mvn spring-boot:run

# ③ order-service :8083（新开窗口）
cd E:\ershou\ershou-cloud\ershou-order-service
mvn spring-boot:run

# ④ gateway :8088（新开窗口；等前面 3 个注册上 Nacos 更稳）
cd E:\ershou\ershou-cloud\ershou-cloud-gateway
mvn spring-boot:run

# ⑤ 旧单体 :8080（新开窗口；未迁移路径的反代目标，必须启动）
cd E:\ershou
java -jar .\ershou-server\target\ershou-server-1.0.0.jar
# 或者用 IDEA 打开旧项目启动 CampusApplication（等价）
```

> 也可以打包后用 jar 启动（每个模块 `target\<artifactId>-1.0.0.jar`）：
> `java -jar .\ershou-cloud\ershou-user-service\target\ershou-user-service-1.0.0.jar`
> 需要换 Nacos 地址时设环境变量：`$env:NACOS_ADDR='127.0.0.1:8848'`。

### 4.6 健康检查（全部就绪后再演示）

```powershell
# ① Nacos 注册列表：应含 4 个服务 + seata-server（Nacos 无鉴权，直接查）
$svc = Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20'
$svc.doms        # 期望：ershou-gateway, ershou-user-service, ershou-item-service, ershou-order-service, seata-server

# ② 端口监听
Get-NetTCPConnection -State Listen -LocalPort 8081,8082,8083,8088,8080 |
  Select-Object LocalPort, OwningProcess

# ③ 网关 → user-service（公开路由）
Invoke-WebRequest 'http://127.0.0.1:8088/api/v1/categories' -UseBasicParsing   # 200

# ④ 网关登录链路（拿 token）
$base = 'http://127.0.0.1:8088/api/v1'
Invoke-RestMethod "$base/auth/sms-code" -Method Post -ContentType 'application/json' -Body '{"phone":"19900000101"}'
$login = Invoke-RestMethod "$base/auth/login" -Method Post -ContentType 'application/json' -Body '{"phone":"19900000101","code":"123456"}'
$h = @{ Authorization = 'Bearer ' + $login.data.token }
Invoke-RestMethod "$base/users/me" -Headers $h          # 200 + user
Invoke-RestMethod "$base/items?pageNum=1&pageSize=5"    # 200 + PageResult（≥10 条总量）

# ⑤ 未迁移路径反代（网关 → 旧单体 8080）
Invoke-WebRequest 'http://127.0.0.1:8088/api/v1/platform/shop/points?campusId=2' -UseBasicParsing   # 200
```

### 4.7 跑演示脚本

```powershell
cd E:\ershou
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1
# 11 步全程经网关 8088（S1 短信码 → S2 登录 → S3 /users/me → S4 商品列表 → S5 商品详情
#  → S6 下单(Seata+库存-1) → S7 我的面交单(Feign 补标题) → S8 debugFail 全局回滚
#  → S9 RabbitMQ sale_count+1 → S10 Sentinel 限流 429 → S11 未迁移路径反代 200）
# 常用参数：-DryRun / -SkipSeata / -SkipRabbit / -SkipSentinel / -SkipLegacyProxy
#           -NoStartLegacy（不自动拉起旧单体）/ -StopLegacyAfter / -Phone 19900000099 / -VerboseBody
# 输出：.build\cloud\demo-result.json（PASS/FAIL + 每步真实响应片段）
# 退出码：0=全部通过（SKIP 不算失败）；2=网关不可达；>0=失败断言数（≤99）
```
> 脚本由 map-member（T29）维护；逐步演示、前端切网关、App 接网关见 [docs/demo-script.md](docs/demo-script.md)（本文不重复）。

### 4.8 前端 / App 接入

- **Web**：Vite 代理目标从 `http://127.0.0.1:8080` 改为 `http://127.0.0.1:8088`（已 env 化），重启 `npm run dev`；页面无需改代码。
- **App**：服务器地址填 `<电脑 IP>:8088`；真机 USB 联调可 `adb reverse tcp:8088 tcp:8088`。

> 具体命令与验证步骤见 [docs/demo-script.md](docs/demo-script.md)（本文不重复）。

---

## 五、端口表

| 端口 | 服务/组件 | 容器/进程 | 说明 |
| --- | --- | --- | --- |
| **8088** | Gateway | 本机 Maven/JVM | 唯一对外入口（Web/App 只认它） |
| 8081 | user-service | 本机 Maven/JVM | 认证/用户/学校校区 |
| 8082 | item-service | 本机 Maven/JVM | 商品读 / `/inner/**` 库存（仅供 Feign） |
| 8083 | order-service | 本机 Maven/JVM | 面交单 / Seata TM / MQ 生产 |
| 8080 | 旧单体 | 本机 JVM | 未迁移路径反代目标（WebSocket `/ws/**` 也走它） |
| 8848 / 9848 | Nacos | `ershou-nacos` | 控制台/OpenAPI；9848=gRPC |
| 5672 / 15672 | RabbitMQ | `ershou-rabbitmq` | AMQP；管理台 |
| 8091 / 7091 | Seata | `ershou-seata` | TC；控制台 |
| 8858 | Sentinel Dashboard | `ershou-sentinel` | 规则/资源可视化 |
| 3306 | MySQL | 本机 mysqld | `ershou` 旧库 + `ershou_user/item/order` 新库 |
| 6380 | Redis | `ershou-redis` | db0 会话共享；db1/2/3 各服务隔离 |
| 9010 / 9011 | MinIO | `ershou-minio` | 图片对象存储（演示） |
| 5173 | Web 前端 | Vite dev | 代理可切 8088 |

---

## 六、常见问题（FAQ）

### 6.1 服务启动失败：Nacos 配置没发布

**现象**：启动报 `Failed to configure a DataSource`、`Could not resolve placeholder`、`No spring.config.import property`、或连空数据源（配置为 `optional:nacos:`，Nacos 不可用/缺配置时进程能起但数据源缺失）。
**处理**：
```powershell
# 1) 打开 Nacos 控制台确认 4 个 dataId 存在、group=DEFAULT_GROUP、含 .yaml 后缀
Start-Process 'http://127.0.0.1:8848/nacos'
# 2) 重新发布（幂等）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-publish-config.ps1
# 3) OpenAPI 复核
(Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/cs/configs?search=accurate&dataId=&group=DEFAULT_GROUP&pageNo=1&pageSize=20').totalCount
```
注意 dataId 必须与 `spring.config.import` 中一致（如 `ershou-user-service.yaml`）。

### 6.2 Seata 未注册 / 下单报「no available service」

**现象**：order-service 启动正常，但 `POST /api/v1/meetup-orders` 报 `no available service 'default' found`、`can not get cluster name`、`io.seata.*` 异常。
**排查**：
```powershell
docker ps --filter name=ershou-seata                    # 容器 running
docker logs --tail 60 ershou-seata                      # 看 registry=nacos 是否连上 8848
# Nacos 服务列表应有 seata-server
Invoke-RestMethod 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20'
```
核对：`ershou-item-service.yaml` / `ershou-order-service.yaml` 的 `seata.registry.type=nacos`、`server-addr=127.0.0.1:8848`、`application=seata-server`、`group=SEATA_GROUP`、`tx-service-group=ershou_tx_group`、`vgroup-mapping.ershou_tx_group=default`；两新库存在 `undo_log` 表；Seata 注册后等 10–30s 重试。
**版本注意**：client 由 SCA BOM 带入 **2.1.0**，server 镜像为 **1.8.0**。若联调出现协议/兼容报错，按设计 §八预案：换 `seataio/seata-server:2.0.0` 镜像（本机已拉取）并同步调整 `docker/seata` 配置，或把 client 降到与 server 一致（由 platform-dev 改 pom）。
**已知坑（容器 IP 注册）**：Seata TC 在 Nacos 的 `SEATA_GROUP/seata-server` 注册的是 **Docker bridge 容器 IP**（如 `172.20.0.5:8091`），Windows 宿主机直连不可达 → 宿主机上的 item/order 作为 Seata 客户端可能连不上 TC（影响 `@GlobalTransactional` 回滚）。处理建议（**改 compose 前先报 Lead 裁决**）：
1. 给 seata 服务加 `hostname: seata-server` + `extra_hosts: ["seata-server:127.0.0.1"]` 让其注册 `127.0.0.1:8091`（宿主机经端口映射可达），重启后用 `GET /nacos/v1/ns/instance/list?serviceName=seata-server&groupName=SEATA_GROUP` 复核 IP；
2. 版本统一（见上）；
3. 降级：Seata 接入/AT 配置就绪，回滚演示改用 TC 日志，不阻塞其他验收。
细节与实测记录见 [docker/README.md §5](docker/README.md)。

### 6.3 端口冲突（8080/8081/8082/8083/8088/8848…）

**现象**：启动报 `Port 808x was already in use` 或 `Web server failed to start`。
**处理**：
```powershell
Get-NetTCPConnection -State Listen -LocalPort 8080,8081,8082,8083,8088 |
  Select-Object LocalPort, OwningProcess
Get-Process -Id <pid> | Select-Object Id, ProcessName, StartTime
Stop-Process -Id <pid>          # 确认是旧进程再杀
```
优先释放端口，**不要随意改端口**：改端口要同步改网关路由、前端/App 地址与验收脚本。旧单体 8080 与网关 8088 是两回事，别混淆。

### 6.4 旧单体没起 → 网关反代 502

**现象**：已迁移接口正常，但 `/api/v1/platform/**`、`/api/v1/admin/**`、`/api/v1/items/mine`、`/ws/**` 等返回 **502 Bad Gateway**，网关日志 `Connection refused: 127.0.0.1:8080`。
**原因**：未迁移路径由网关反代到旧单体 8080，单体没启动/未就绪。
**处理**：启动旧单体（§4.5 ⑤），等 8080 监听后重试；单体后起也没关系，路由动态转发，就绪即恢复。
演示/验收前确认：`Get-NetTCPConnection -LocalPort 8080 -State Listen`。

### 6.5 PowerShell 脚本编码坑（中文乱码/解析失败）

**现象**：运行 `scripts/*.ps1` 输出中文乱码、或报 `Unexpected token`/「字符串缺少终止符」。
**原因**：Windows PowerShell 5.1 读取 **无 BOM 的 UTF-8 脚本**时按 ANSI 解码，中文变成乱码导致解析错误。
**处理**：
```powershell
# 1) 始终用 -File 方式执行脚本（不要用 cmd /c 拼中文参数）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-publish-config.ps1
# 2) 如终端仍乱码，先切 UTF-8 控制台
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 3) 自己改/写 .ps1 时保存为「UTF-8 带 BOM」
#    PowerShell 5.1 示例：$c = Get-Content x.ps1 -Raw; Set-Content x.ps1 $c -Encoding UTF8
```
另：日志重定向用 `*>`（PowerShell 全流）或 `| Tee-Object -FilePath x.log`，避免 `cmd >` 造成的编码/换行问题。

### 6.6 迁移脚本行数/汇总异常

**现象**：`migrate-data.ps1` 逐表输出「旧库 N 行 -> 新库 N 行 [OK]」，但末尾「新库表行数」全显示 0。
**原因**：汇总段统计口径/连接账号权限问题（逐表迁移用的是 root 跨库连接，汇总段用业务账号时可能看不到新库）。逐表行数才是权威结果。
**复核**（各库独立账号，见 §4.2 命令）：当前基线应为 `sys_user=190 / school=1 / campus=3 / item=17 / item_category=10 / meetup_order=2`，合计 223。若需修复汇总段，反馈给脚本维护者（chat-member / T28）。

### 6.7 其他常见问题

| 现象 | 排查/解决 |
| --- | --- |
| `docker compose up` 失败 / 镜像拉不到 | 先确认 Docker Desktop 引擎已启动（`docker info`）；Nacos/Rabbit/Seata 镜像已预拉；Sentinel Dashboard 用 `ershou-cloud/docker/sentinel` 自建（官方镜像 403 预案） |
| Nacos 启动慢/连不上 | 首次 healthy 约 40–60s；等 `docker compose ps` 里 nacos healthy；或 `NACOS_ADDR` 写错（默认 `127.0.0.1:8848`） |
| 服务注册上了但网关 503/404 | 路由名要与 `spring.application.name` 一致；等 Nacos 心跳（默认 15s）；确认网关拉到了 `ershou-gateway.yaml` |
| `mvn spring-boot:run` 报找不到 common 依赖 | 先执行 §4.4 的 `mvn -f .\ershou-cloud\pom.xml -DskipTests install` |
| 新旧库数据对不上 | 一次性快照的已知边界：旧单体写旧库、新服务写新库；演示前重跑迁移脚本对齐（策略见 [migration-roadmap.md §4](docs/migration-roadmap.md)） |
| Redis 隔离断言失败 | 检查 Nacos 配置里各服务 `spring.data.redis.database`（1/2/3）与网关 db0；业务 key 必须带 `ershou:<service>:` 前缀 |
| `mvn` 依赖下载超时 | 配置国内 Maven 镜像后重试；首次构建耐心等待 |
| Web/App 连不上网关 | Web 改 Vite 代理到 8088 并重启 dev；App 服务器地址 `host:8088`，真机 `adb reverse tcp:8088 tcp:8088` |
| 演示脚本失败 | 先看 `.build/` 下日志；按 demo-script 的分步命令定位到具体环节（登录/商品/下单/回滚/限流/反代） |

---

## 七、关闭与清理

```powershell
# 1) 停 4 个新服务 + 旧单体：在各窗口 Ctrl+C，或
Get-NetTCPConnection -State Listen -LocalPort 8081,8082,8083,8088,8080 |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -ErrorAction SilentlyContinue }

# 2) 停中间件容器（默认保留数据卷：下次 up 后 Nacos 配置/RabbitMQ 数据还在）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1
# 仅看计划：-DryRun；连数据卷一起删（会清空 Nacos 已发布配置，慎用）：-RemoveVolumes
# 等价原生命令：docker compose --env-file .env -f .\ershou-cloud\docker\docker-compose.yml down

# 3) 既有容器（MySQL/Redis/MinIO/ES）不要删除，后续轮次仍要用；cloud-down 不会动它们
```

---

## 八、验收对照与说明

- 本轮验收标准（Docker/构建/注册/分库/网关链路/Seata/MQ/Sentinel/配置动态/前端接入/Redis 隔离/文档）见设计文档 §7；QA 逐条取证见 `智能体团队/第5轮-SpringCloud重构-验收.md`（QA 产出）。
- 一键演示与逐步演示：[docs/demo-script.md](docs/demo-script.md)。
- **约束提醒**：本轮旧单体 `ershou-server/**` 不改代码；数据迁移只读旧库；迁移后新旧库不同步（演示边界）；所有密码/token 均为演示值。
- 路由特殊说明：`GET /api/v1/items/mine` 与其余 `/api/v1/users/**` 反代旧单体（写路径仍在单体）；`/api/v1/categories` 路由到 item-service（分类表在 `ershou_item` 库）。完整路由表见 [docs/architecture.md §2.1](docs/architecture.md)。

---

> 最后更新：2026-10-19（T30 文档）｜维护：`app-member`；架构与端口以 [docs/architecture.md](docs/architecture.md) 为准，若脚本/配置有调整请同步更新本文。
