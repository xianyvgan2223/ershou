# 第 5 轮 · Spring Cloud 中间件（Docker）

本目录是 [`智能体团队/第5轮-SpringCloud重构-设计.md`](../../智能体团队/第5轮-SpringCloud重构-设计.md) 的中间件编排：
**Nacos（注册+配置）/ RabbitMQ / Seata TC / Sentinel Dashboard** 四容器一键起停。
微服务本体（gateway/user/item/order）在宿主机用 `mvn spring-boot:run` 启动；Redis / MySQL / MinIO / ES 沿用既有容器，不在此编排内。

---

## 1. 快速开始

```powershell
# 启动（含 sentinel 镜像 --build、等健康、发布 Nacos 配置、冒烟验收）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1

# 只看计划（不启动）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1 -DryRun

# 停止（默认保留数据卷；-RemoveVolumes 会清空 Nacos 配置）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1 -DryRun

# 等价的原生命令
docker compose -f ershou-cloud/docker/docker-compose.yml up -d --build
docker compose -f ershou-cloud/docker/docker-compose.yml down
```

证据输出：`.build/cloud/logs/cloud-up-<时间戳>.log`（全过程）与 `.json`（结构化断言）。

## 2. 容器与端口

| 容器 | 镜像 | 端口（宿主→容器） | 就绪判据 | 说明 |
| --- | --- | --- | --- | --- |
| `ershou-nacos` | `nacos/nacos-server:v2.3.2` | 8848→8848、9848→9848 | healthcheck `curl /nacos/v1/console/health/readiness` | MODE=standalone、`NACOS_AUTH_ENABLE=false`；volume `nacos-data/nacos-logs` |
| `ershou-rabbitmq` | `rabbitmq:3-management` | 5672→5672、15672→15672 | healthcheck `rabbitmq-diagnostics -q ping` | guest/guest；volume `rabbitmq-data` |
| `ershou-seata` | `seataio/seata-server:1.8.0` | 8091→8091（TC）、7091→7091（控制台） | 无 healthcheck，看 running + 端口 | registry=nacos（`nacos:8848`，group=SEATA_GROUP）、store=file；控制台 seata/seata |
| `ershou-sentinel` | `ershou/sentinel-dashboard:1.8.8`（**本地自建**） | 8858→8858 | 无 healthcheck，看 running + HTTP 200 | dockerfile 见 `sentinel/Dockerfile` |

统一网络：`ershou-cloud-net`（bridge）。容器之间用服务名互访（如 `nacos:8848`）；**宿主机上的服务用 `127.0.0.1:<端口映射>`**。

## 3. 账号与配置

| 项 | 值 |
| --- | --- |
| Nacos | 开发模式关闭鉴权（`NACOS_AUTH_ENABLE=false`）；控制台 <http://127.0.0.1:8848/nacos>；OpenAPI `POST /nacos/v1/cs/configs` |
| RabbitMQ | guest / guest；AMQP `127.0.0.1:5672`；管理台 <http://127.0.0.1:15672> |
| Seata 控制台 | seata / seata；<http://127.0.0.1:7091> |
| Seata 注册信息 | Nacos `SEATA_GROUP / seata-server`，cluster `default`，客户端 `tx-service-group=ershou_tx_group`（vgroup-mapping → default） |
| Sentinel | 无登录；<http://127.0.0.1:8858>（默认账号 sentinel/sentinel，若开启鉴权） |
| MySQL 分库 | `ershou_user/ershou_item/ershou_order`，独立账号同名，密码同 `.env` 的 `MYSQL_PASSWORD` —— 见 [`../sql/init-cloud-databases.sql`](../sql/init-cloud-databases.sql) 与 [`../sql/migrate-data.ps1`](../sql/migrate-data.ps1) |
| 既有 Redis | `127.0.0.1:6380`（容器 `ershou-redis`，不在本 compose）；db0=Sa-Token 会话、db1=user、db2=item、db3=order |

## 4. 两个已踩过的坑（重要）

### 4.1 Seata 1.8.0 镜像是 Jib 结构，没有 registry.conf / file.conf
`seataio/seata-server:1.8.0` 的入口是 `/seata-server-entrypoint.sh`，只处理 JVM 参数；**配置只有一个文件**：
`/seata-server/resources/application.yml`。因此本仓库用
`volumes: - ./seata/application.yml:/seata-server/resources/application.yml:ro`
挂载配置，**不要**再去找 `registry.conf`/`file.conf`（对 1.8.0 Jib 镜像无效）。
配置内容：`server.port=7091`、`seata.server.service-port=8091`、`registry.type=nacos`（`nacos:8848`，group `SEATA_GROUP`）、`store.mode=file`、`config.type=file`。

### 4.2 Sentinel 官方镜像拉不到 → 自建镜像
`bladex/sentinel-dashboard` 等镜像在本机被 403 拦截。改为：
- 从 GitHub Release 下载 `sentinel-dashboard-1.8.8.jar` 到 `ershou-cloud/docker/sentinel/sentinel-dashboard.jar`
  （`https://github.com/alibaba/Sentinel/releases/download/1.8.8/sentinel-dashboard-1.8.8.jar`，约 22 MB）；
- `sentinel/Dockerfile` 用 `eclipse-temurin:17-jre`（本地已有）`COPY` 进镜像，启动参数 `-Dserver.port=8858`；
- `scripts/cloud-up.ps1` 会检查该 jar；缺失且无现成镜像时会明确报错（有镜像则自动 `-SkipBuild`）。

## 5. Seata TC 注册 IP 问题：现状与可选改进（SEATA_IP + registry=nacos）

### 5.1 现象与根因（2026-10-19 实测）
Nacos 的 `SEATA_GROUP/seata-server` 实例 IP 是 Docker bridge 容器 IP（如 `172.20.0.5:8091`），
Windows 宿主机 `Test-NetConnection 172.20.0.5 -Port 8091` → **False**，宿主机上的 item/order 服务作为
Seata 客户端按 Nacos 注册地址连 TC 会失败：
`will connect to 172.20.0.5:8091` → `can not connect to services-server`。
另：`--network host` 在本机 Docker Desktop 上不可用（实测 host 网络下 127.0.0.1 不暴露端口）。

### 5.2 本轮采用的方案（**已实测跑通，保持不动**）
由 client 侧解决（platform-dev 实施，配置在 Nacos 的 `ershou-item-service.yaml` / `ershou-order-service.yaml`）：
- seata client 统一为 **`io.seata` 1.8.0**（parent 统一管理，排除 SCA 默认的 `org.apache.seata` 2.1.0，与 server 1.8.0 对齐）；
- 客户端 `seata.registry.type: file` + `seata.service.grouplist.default: 127.0.0.1:8091`
  （Seata 1.8 客户端在 `registry=nacos` 时会忽略 grouplist，故必须用 file；compose 已把 8091 映射到宿主机，直连可达）。
- QA 独立复核通过（`.build/cloud/logs/order-service-seata.log`）：
  `will connect to 127.0.0.1:8091` → `register TM success. client version:1.8.0, server version:1.8.0` →
  `register RM success ... 1.8.0/1.8.0`。
> 联调期间**不动 TC 配置**（降低风险）；本轮验收即以此方案为准。

### 5.3 可选改进（**本轮未启用**，供后续完善）：切回 `registry=nacos`
目标：让演示更贴合设计原意「Seata 通过 Nacos 注册发现 TC」。技术依据（QA 反编译 + 隔离实验，2026-10-19）：
- 该 1.8.0 Jib 镜像内 `io/seata/server/env/ContainerHelper.class` 支持环境变量
  **`SEATA_IP` / `SEATA_PORT` / `STORE_MODE`**；`ParameterParser` 在 host 为空时取 `ContainerHelper.getHost()`
  （即 env `SEATA_IP`），`Server` 用它设置 XID/注册 IP。
- **注意：`SEATA_IP=127.0.0.1` 不生效**：`Server` 用 `NetUtil.isValidIp(host, false)` 校验，回环地址被拒后回退容器 IP
  （隔离实验：临时容器 `SEATA_IP=127.0.0.1` 仍注册成 `172.20.0.6:8091`）。
- **`SEATA_IP=<宿主可达的非回环 IP>` 生效**（隔离实验：`SEATA_IP=192.168.65.254` → Nacos 注册 `192.168.65.254:8091`）；
  该 IP 必须从宿主机可达（实测宿主 → WLAN IP `10.34.14.246:8091` 可达；宿主 → `192.168.65.254:8091` 不可达）。
  演示机 IP 可能随 WiFi 变化，因此建议用环境变量注入，别写死。

2 分钟切换路径（等日后排期，需 Lead 授权改 compose）：
```yaml
# docker-compose.yml 的 seata 服务，environment 追加一行（SEATA_IP_HOST 为宿主机局域网 IP）
environment:
  - TZ=Asia/Shanghai
  - SEATA_IP=${SEATA_IP_HOST}        # 例如 10.34.14.246（必须宿主可达、非 127.0.0.1）
```
1. `docker compose -f ershou-cloud/docker/docker-compose.yml up -d seata` 重启 TC；
2. 复核注册地址：`GET /nacos/v1/ns/instance/list?serviceName=seata-server&groupName=SEATA_GROUP`
   → `ip` 应为 `SEATA_IP_HOST`（不再是 172.x）；
3. platform-dev 把 Nacos 两个 dataId 的 `seata.registry.type` 从 `file` 改回 `nacos`（`grouplist` 可保留，无害），
   重新 POST 发布（`scripts/cloud-publish-config.ps1` 或 OpenAPI）；
4. 重启 8082/8083（seata registry 属启动期配置，配置中心刷新不生效）；
5. 验收：服务日志 `will connect to <SEATA_IP_HOST>:8091` + `register RM success`。
> 隔离实验用独立应用名 `seata-server-qa` 完成，临时容器与 Nacos 临时服务均已清理，未影响正式 `seata-server`。

### 5.4 降级预案（设计 §八）
即使不切回 nacos registry，当前 AT 链路已通（file + grouplist），`@GlobalTransactional` 回滚演示可正常做；
若 Seata 出现不可恢复问题，可用「Seata 接入 + AT 配置就绪、rollback 用 TC 日志说明」的降级口径，不阻塞其他验收。

## 6. 健康检查命令（手工排查）

```powershell
docker ps --format '{{.Names}} | {{.Status}} | {{.Ports}}'
docker inspect --format '{{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' ershou-nacos ershou-rabbitmq ershou-seata ershou-sentinel
Invoke-WebRequest http://127.0.0.1:8848/nacos/v1/console/health/readiness -UseBasicParsing
Invoke-WebRequest 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=50' -UseBasicParsing
Invoke-WebRequest 'http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=seata-server&groupName=SEATA_GROUP' -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:15672/ -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:7091/ -UseBasicParsing
Invoke-WebRequest http://127.0.0.1:8858/ -UseBasicParsing
```

## 7. 启动顺序（演示时）

1. `scripts/cloud-up.ps1`（中间件 + 发布 Nacos 配置）
2. 旧单体 `ershou-server` :8080（网关反代目标；未迁移路径需要它）
3. `ershou-user-service` :8081 → `ershou-item-service` :8082 → `ershou-order-service` :8083 → `ershou-gateway` :8088
4. Web 5173 代理指向 8088 / App 服务器地址填 `host:8088`

## 8. Nacos 配置发布

`scripts/cloud-publish-config.ps1`：把 [`../nacos-configs/`](../nacos-configs) 下 4 个 `*.yaml` 通过 OpenAPI 发布/覆盖到
`DEFAULT_GROUP`（dataId=文件名）。`cloud-up.ps1` 会自动调用它；也可单独执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-publish-config.ps1
```

校验：`GET http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=ershou-gateway.yaml&group=DEFAULT_GROUP` 返回 YAML 正文。

## 9. 目录说明

| 文件 | 作用 |
| --- | --- |
| `docker-compose.yml` | 四容器编排（网络 `ershou-cloud-net`、volume、healthcheck、依赖关系） |
| `seata/application.yml` | Seata 1.8.0 Jib 镜像唯一配置（挂载进容器） |
| `sentinel/Dockerfile` | Sentinel Dashboard 自建镜像（FROM eclipse-temurin:17-jre） |
| `sentinel/sentinel-dashboard.jar` | Sentinel 1.8.8 jar（GitHub Release 下载，**不入库**） |
| `Dockerfile.service` | 通用微服务镜像（`ARG JAR_FILE`，把 gateway 等 fat jar 打进 `eclipse-temurin:17-jre`） |

`Dockerfile.service` 构建示例（构建上下文为 `ershou-cloud/`）：

```powershell
cd E:\ershou
docker build -f ershou-cloud/docker/Dockerfile.service `
  --build-arg JAR_FILE=ershou-cloud-gateway/target/ershou-cloud-gateway-1.0.0.jar `
  -t ershou/ershou-gateway:dev ershou-cloud
docker run --rm --entrypoint sh ershou/ershou-gateway:dev -c 'ls -l /app/app.jar && java -version'
```
