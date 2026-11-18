# 平台演示剧本 · Spring Cloud 微服务重构（第 5 轮）

> 本文是**一步步照着做就能演示**的操作剧本：启动顺序 → 手工 curl 逐步验证 → 一键断言脚本。
> 架构细节看 [`architecture.md`](./architecture.md)，剩余模块迁移计划看 [`migration-roadmap.md`](./migration-roadmap.md)，
> 完整契约看 `智能体团队/第5轮-SpringCloud重构-设计.md`。
>
> 本文由 `map-member`（T29）维护，覆盖**演示脚本部分 + 前端接网关**；架构/路线图文档由 `app-member` 维护。
> 所有命令都在仓库根目录 `E:\ershou` 下执行，PowerShell 用 `powershell -ExecutionPolicy Bypass -File .\xxx.ps1`。

---

## 0. 演示目标（30 秒版）

这一轮把单体拆成一圈**可运行、可演示**的微服务切片，演示时能看到 8 件事：

1. **网关统一入口 8088**：Web/App 只认 8088，`/api/v1/**` 路径完全不变（前端只改代理端口）。
2. **3 个微服务**：user 8081（登录/用户/校区）、item 8082（商品读 + 内部锁库存 + MQ 消费）、order 8083（面交单 + Feign + Seata + MQ 生产）。
3. **Nacos**：服务注册发现 + 配置中心（4 个 dataId；改配置 `@RefreshScope` 生效）。
4. **Seata AT 全局事务**：`POST /meetup-orders` 内部 Feign 锁库存 + 订单落库；`debugFail=true` 时**库存回滚、订单不落库**。
5. **RabbitMQ 异步解耦**：下单成功后 `order.created` 事件 → item-service 异步累加 `item.sale_count`。
6. **Sentinel 限流**：Gateway 对 `GET /api/v1/items` 配 QPS=5，压测出现 **429**。
7. **绞杀者反代**：未迁移的旧路径（platform/map/admin/chat/...）由网关反代到旧单体 8080，**功能不中断**。
8. **数据隔离**：MySQL 三库独立账号；Redis db0（会话）/db1（user）/db2（item）/db3（order）各管各的 key 前缀。

---

## 1. 前置条件 checklist

| 项 | 要求 | 检查命令 / 预期 |
| --- | --- | --- |
| 操作系统 | Windows 10/11 + Windows PowerShell 5.1 | `$PSVersionTable.PSVersion`（5.1.x） |
| JDK | **JDK 21**（单体与 5 个模块都用 21） | `java -version` → `21.x` |
| Maven | 本机无全局 mvn，用 IDEA 自带（脚本会探测） | `Test-Path 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'` |
| Node | 18+（前端） | `node -v` |
| Docker Desktop | 运行中，可 `docker ps` | `docker version --format '{{.Server.Version}}'` |
| 既有容器 | `ershou-redis`(6380) / MySQL(3306) / `ershou-es`(9211) / `ershou-minio`(9010) 保持运行 | `docker ps --format "{{.Names}}"` |
| 凭证 | 仓库根 `.env` 存在（MySQL/Redis 等）；**不要提交** | `Test-Path .env` → True |
| 端口空闲 | 8080/8081/8082/8083/8088/8848/9848/5672/15672/8091/7091/8858 | 演示前 `Get-NetTCPConnection -LocalPort 8088` 应为空 |
| 演示账号 | 短信 mock 码固定 `123456`；脚本用 `19900000099`（不存在会自动注册）；后台管理员 `19900000102` | — |
| 前端依赖 | `ershou-web/node_modules` 已装 | `Test-Path ershou-web\node_modules` |

> 端口冲突是演示最常见的翻车点：**旧单体 8080 只能有一个实例**（IDEA 起了就不要再让脚本起）。
> `scripts/cloud-demo.ps1` 检测到 8080 已监听时**不会**再起，也**绝不 kill** 别人的进程。

---

## 2. 启动顺序（6 步，务必按顺序）

### 第 1 步 · 一键起中间件 + 发布 Nacos 配置

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1
```

- 该脚本做 5 件事：校验 compose/镜像 → `docker compose up -d`（Nacos / RabbitMQ / Seata / Sentinel）→ 等四容器就绪 → 调 `scripts/cloud-publish-config.ps1` 把 `ershou-cloud/nacos-configs/*.yaml` 发布成 4 个 dataId → 冒烟验收。
- 常用参数：`-DryRun`（只看计划）、`-SkipBuild`（镜像已最新，省 1 分钟）、`-SkipPublish`（不重发配置）。
- **预期输出**（关键行）：

```
[ OK ] Nacos 已就绪
[ OK ] nacos / rabbitmq / seata / sentinel 四容器就绪
[PASS] 4 个 dataId 可读: ershou-gateway.yaml / ershou-user-service.yaml /
       ershou-item-service.yaml / ershou-order-service.yaml
退出码：0
证据：.build/cloud/logs/cloud-up-<时间戳>.log / .json
```

手工验证：

```powershell
docker ps --format "table {{.Names}}\t{{.Status}}"   # nacos/rabbitmq/seata/sentinel 均 Up (healthy 或 running)
(Invoke-WebRequest 'http://127.0.0.1:8848/nacos/v1/console/health/readiness' -UseBasicParsing).StatusCode  # 200
(Invoke-WebRequest 'http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=ershou-gateway.yaml&group=DEFAULT_GROUP' -UseBasicParsing).StatusCode  # 200
(Invoke-WebRequest 'http://127.0.0.1:15672/api/overview' -Headers @{Authorization='Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))} -UseBasicParsing).StatusCode  # 200
```

> 中间件说明：Nacos `8848`(+9848 gRPC)、RabbitMQ `5672`/管理台 `15672`（guest/guest）、Seata TC `8091`/控制台 `7091`、Sentinel Dashboard `8858`。
> Redis 沿用既有容器 `ershou-redis`（**6380**），本套脚本不接管、不停止它。

### 第 2 步 · 建分库 + 迁移数据（只需执行一次，可重复执行）

```powershell
# 2.1 建库 + 建表 + 独立账号（幂等；密码统一取 .env 的 MYSQL_PASSWORD，见设计 §2.1）
powershell -ExecutionPolicy Bypass -File .\scripts\run-sql.ps1 -File .\ershou-cloud\sql\init-cloud-databases.sql

# 2.2 从旧库 ershou 快照迁移到三库（幂等：先 TRUNCATE 再 INSERT SELECT；只读旧库）
powershell -ExecutionPolicy Bypass -File .\ershou-cloud\sql\migrate-data.ps1
```

- 迁移脚本自动从仓库根 `.env` 取旧库凭据（`MYSQL_ROOT_USER`/`MYSQL_ROOT_PASSWORD` 等），日志写 `.build/cloud/logs/migrate-data-<时间戳>.log`。
- **预期输出**（逐表 OK）：

```
--- ershou_user.sys_user  <=  ershou.sys_user ---   旧库 190 行 -> 新库 190 行  [OK]
--- ershou_user.school    <=  ershou.school ---     旧库 1 行   -> 新库 1 行    [OK]
--- ershou_user.campus    <=  ershou.campus ---     旧库 3 行   -> 新库 3 行    [OK]
--- ershou_item.item      <=  ershou.item ---       旧库 17 行  -> 新库 17 行   [OK]（sale_count 默认 0）
--- ershou_item.item_category <= ... ---            旧库 10 行  -> 新库 10 行   [OK]
--- ershou_order.meetup_order <= ... ---            旧库 2 行   -> 新库 2 行    [OK]
退出码：0
```

手工验证（三库账号互相独立）：

```powershell
$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item --password=$env:MYSQL_PASSWORD --database=ershou_item -N -B -e "SELECT COUNT(*), SUM(status=2) FROM item;"      # 17  16
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_order --password=$env:MYSQL_PASSWORD --database=ershou_order -N -B -e "SELECT COUNT(*) FROM meetup_order;"           # 2
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_user --password=$env:MYSQL_PASSWORD --database=ershou_user -N -B -e "SELECT COUNT(*) FROM sys_user;"                 # 190
```

> 注意：迁移是**一次性快照**（设计 §2.1）。迁移后新服务的写操作不会回写旧库 `ershou`，旧库也未被子脚本修改。

### 第 3 步 · 启动 4 个微服务

推荐 **IDEA 里分别 Run 4 个 Application 类**（各开一个 Run 窗口，便于看日志）；也可以用命令行（本机无全局 mvn，用 IDEA 自带）：

```powershell
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'
& $mvn -f .\ershou-cloud\pom.xml -pl ershou-user-service  spring-boot:run   # 8081
& $mvn -f .\ershou-cloud\pom.xml -pl ershou-item-service  spring-boot:run   # 8082
& $mvn -f .\ershou-cloud\pom.xml -pl ershou-order-service spring-boot:run   # 8083
& $mvn -f .\ershou-cloud\pom.xml -pl ershou-cloud-gateway spring-boot:run   # 8088（最后起，等前三者注册）
```

- 每个服务 Console 预期出现：`Started XxxApplication in N seconds`，且 Nacos 注册成功（无 `NacosRegistration` 报错）。
- **验证注册**：

```powershell
(Invoke-WebRequest 'http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20' -UseBasicParsing).Content
# 预期: {"count":4,"doms":["ershou-gateway","ershou-item-service","ershou-order-service","ershou-user-service"]}
foreach ($p in 8081,8082,8083,8088) { (Test-NetConnection 127.0.0.1 -Port $p -InformationLevel Quiet) }  # 全 True
```

### 第 4 步 · 启动旧单体 8080（绞杀者反代的兜底）

三选一（**只能一个实例**）：

```powershell
# A. IDEA 里 Run CampusApplication（推荐，用户平时就这么起）
# B. 用现有脚本（会拉起 Maven spring-boot:run）
powershell -ExecutionPolicy Bypass -File .\scripts\dev-server.ps1
# C. 已构建 jar 直接起（最快）
java -jar .\ershou-server\target\ershou-server-1.0.0.jar --spring.profiles.active=dev,local
```

- **预期**：`http://127.0.0.1:8080/api/v1/categories` 返回 `{"code":0,...}`。
- 说明：网关把 `POST/PUT/DELETE /api/v1/items/**`、`/api/v1/meetup-orders/**` 之外未迁移的模块、`/ws/**` 都反代到 8080（见设计 §4.1）。

### 第 5 步 · （可选）前端切到网关

见本文第 3 节「前端如何切到网关」。切完打开 `http://127.0.0.1:5173`，登录/首页/详情/面交单都应正常。

### 第 6 步 · 跑端到端断言脚本

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1
```

- 脚本会自动完成 **11 步断言**（第 4 节逐步说明），输出 PASS/FAIL 表并写 `.build/cloud/demo-result.json`。
- **预期**：

```
================ 汇总 ================
  S1   PASS  短信验证码
  S2   PASS  登录（Sa-Token 会话写入 Redis db0）
  S3   PASS  当前用户（网关 X-User-Id 透传）
  S4   PASS  商品列表（item-service）
  S5   PASS  商品详情
  S6   PASS  创建面交单（Feign 锁库存 + 订单落库）
  S7   PASS  我的面交单（Feign 跨服务补商品标题）
  S8   PASS  Seata 全局回滚（debugFail=true）
  S9   PASS  RabbitMQ 异步消费（item.sale_count 增加）
  S10  PASS  Gateway Sentinel 限流（QPS=5）
  S11  PASS  未迁移路径经网关反代旧单体 8080
  PASS=11  FAIL=0  SKIP=0  退出码: 0
```

- 旧单体没起时脚本会尝试后台拉起（javar jar 优先，其次 Maven），日志落 `.build/cloud/logs/legacy-*.log`；
  不想让它拉起就加 `-NoStartLegacy`（此时 S11 记 SKIP）。
- 其他常用参数：`-SkipSeata` / `-SkipRabbit` / `-SkipSentinel` / `-SkipLegacyProxy` / `-DryRun` / `-VerboseBody` / `-MySQLPath`。

---

## 3. 前端如何切到网关

> 核心事实：**网关完整保留 `/api/v1/**` 路径**，所以前端业务代码零改动，只需要把 Vite 开发代理的 `/api` 目标从旧单体 `8080` 换成网关 `8088`。

### 3.1 已做的前端改动（第 5 轮）

| 文件 | 改动 |
| --- | --- |
| `ershou-web/vite.config.ts` | 代理目标改为 env 化：`process.env.VITE_PROXY_TARGET \|\| localEnv.VITE_PROXY_TARGET \|\| 'http://127.0.0.1:8080'`；`/api` 与 `/ws` 都跟随该值。**默认仍 8080，老流程不变** |
| `ershou-web/.env.cloud` | `VITE_PROXY_TARGET=http://127.0.0.1:8088`（cloud 模式专用，无敏感信息） |

### 3.2 切到网关（二选一）

```powershell
cd E:\ershou\ershou-web

# 方式 A（推荐，演示用）：加载 .env.cloud，代理 /api、/ws 到 8088
npm run dev -- --mode cloud

# 方式 B：不改文件，直接给环境变量（优先级最高，可临时指向任意端口）
$env:VITE_PROXY_TARGET='http://127.0.0.1:8088'; npm run dev
```

启动后浏览器打开 `http://127.0.0.1:5173`：

- F12 → Network 里所有 `/api/v1/*` 请求都发往 `127.0.0.1:5173`，由 Vite 代理到 `8088`（看响应头/后端网关日志确认）。
- 登录（`19900000099` / `123456`）→ 首页列表 → 商品详情 → 我的面交 全部应正常。
- 未迁移页面（运营后台 `/admin`、平台严选 `/platform`、校内地图 `/map`、私聊 `/chat`）也正常 —— 它们经网关反代到 8080。

### 3.3 切回旧单体（不破坏原流程）

```powershell
cd E:\ershou\ershou-web
npm run dev          # 不带 --mode cloud，默认代理 http://127.0.0.1:8080
```

### 3.4 构建产物与生产部署（重要）

`npm run build` **不经过** Vite 代理（代理只在 dev server 生效），所以：

```powershell
cd E:\ershou\ershou-web
npm run build        # 产物 dist/ 与是否切网关无关；构建不依赖网关是否启动
```

生产/演示用 dist 时，由 Nginx 同源反代（与当前生产形态一致）：

```nginx
location /api/ { proxy_pass http://127.0.0.1:8088; proxy_set_header Host $host; }
location /ws/  { proxy_pass http://127.0.0.1:8088; proxy_http_version 1.1;
                 proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }
```

### 3.5 移动端（App）

App 不改代码，只需在「连接/设置」里把服务器地址填成 **`http://<电脑局域网 IP>:8088`**（原来填 8080）。
网关与 App 的 WebSocket 路径 `/ws/**` 仍反代旧单体，私聊不受影响。

---

## 4. 演示 11 步（手工 curl 版 + 预期输出）

> 一键脚本 `scripts/cloud-demo.ps1` 就是把这 11 步自动化。想手动演示时按下表逐条敲。
> 先准备变量（PowerShell）：

```powershell
$G = 'http://127.0.0.1:8088/api/v1'      # 网关
$PHONE = '19900000099'
$TOKEN = ''                               # S2 后填
```

### S1 短信验证码（user-service，经网关）

```powershell
curl.exe -s -X POST "$G/auth/sms-code" -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\"}"
```

**预期**：`{"code":0,"message":"success","data":null,...}`
（mock 码固定 `123456`，同时写 Redis db1 `ershou:user:sms:code:{phone}` TTL 5 分钟）

### S2 登录（拿 token；Sa-Token 会话写 Redis db0）

```powershell
$login = curl.exe -s -X POST "$G/auth/login" -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"code\":\"123456\"}" | ConvertFrom-Json
$TOKEN = $login.data.token
$TOKEN.Length        # 预期 > 0
$login.data.user.id  # 预期 userId（新号会自动注册）
```

**预期**：`code=0`，`data.token` 非空，`data.user` 字段与旧单体 `/auth/login` 完全一致。
> 会话写 Redis **db0**（与网关/旧单体共享，所以网页端登录后访问反代旧路径也带态）。

### S3 当前用户（网关透传 X-User-Id → user-service）

```powershell
curl.exe -s "$G/users/me" -H "Authorization: Bearer $TOKEN"
```

**预期**：`code=0`，`data.id` 与 S2 的 userId 一致。

### S4 商品列表（item-service）

```powershell
curl.exe -s "$G/items?pageNum=1&pageSize=10"
```

**预期**：`code=0`，`data.records` ≥ 5 条（库里有 16 件在售），字段与旧单体 `ItemVO` 对齐。
记下首条 `id` 作为 `$ITEM_ID`。

### S5 商品详情

```powershell
curl.exe -s "$G/items/$ITEM_ID"
```

**预期**：`code=0`，`data.id == $ITEM_ID`。
（item-service 详情缓存 `ershou:item:detail:{id}` 60s，浏览量 `INCR ershou:item:view:{id}`）

### S6 创建面交单（Seata AT：Feign 锁库存 + 订单落库）

记下库存（DB 真值）：

```powershell
$mysql='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item --password=$env:MYSQL_PASSWORD --database=ershou_item -N -B -e "SELECT id,title,stock FROM item WHERE id=$ITEM_ID;"
```

下单（**注意用库存 > 0 的商品**）：

```powershell
curl.exe -s -X POST "$G/meetup-orders" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" `
  -d "{\"itemId\":$ITEM_ID,\"quantity\":1,\"remark\":\"cloud-demo 演示下单\"}"
```

**预期**：`code=0`，`data.id` / `data.orderNo` 有值。
**DB 证据**（设计 §七.5）：

```powershell
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item --password=$env:MYSQL_PASSWORD --database=ershou_item -N -B -e "SELECT stock FROM item WHERE id=$ITEM_ID;"     # 比下单前 -1
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_order --password=$env:MYSQL_PASSWORD --database=ershou_order -N -B -e "SELECT id,order_no,buyer_id,item_id,quantity,status FROM meetup_order ORDER BY id DESC LIMIT 1;"
# 预期：status=2（待面交/待自提）、quantity=1
```

### S7 我的面交单（Feign 跨服务补商品标题/封面）

```powershell
curl.exe -s "$G/meetup-orders/mine?pageNum=1&pageSize=20" -H "Authorization: Bearer $TOKEN"
```

**预期**：`code=0`，`data.records` 含 S6 刚创建的订单；`itemTitle` 非空（order-service 通过 Feign 调 item-service 内部接口拼装）。

### S8 Seata 全局回滚（debugFail=true）

先记另一个可购商品的库存与订单数：

```powershell
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item --password=$env:MYSQL_PASSWORD --database=ershou_item -N -B -e "SELECT id,stock FROM item WHERE status=2 AND stock>0 ORDER BY id LIMIT 3;"
$RB_ID = 18   # 换成一个 stock>0 且不等于 $ITEM_ID 的商品
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item --password=$env:MYSQL_PASSWORD --database=ershou_item -N -B -e "SELECT stock FROM item WHERE id=$RB_ID;"
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_order --password=$env:MYSQL_PASSWORD --database=ershou_order -N -B -e "SELECT COUNT(*) FROM meetup_order WHERE item_id=$RB_ID;"
```

触发回滚：

```powershell
curl.exe -s -X POST "$G/meetup-orders" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" `
  -d "{\"itemId\":$RB_ID,\"quantity\":1,\"debugFail\":true,\"remark\":\"Seata rollback 演示\"}"
```

**预期**：请求**失败**（HTTP 500 或 `code != 0`），服务端日志出现 `Rollback` 相关输出；然后：

```powershell
& $mysql ... --database=ershou_item  -N -B -e "SELECT stock FROM item WHERE id=$RB_ID;"          # 与触发前一致（库存回滚）
& $mysql ... --database=ershou_order -N -B -e "SELECT COUNT(*) FROM meetup_order WHERE item_id=$RB_ID;"  # 与触发前一致（订单未落库）
```

**证据点**：order-service 控制台日志里 `[Seata] Rollback` / `io.seata` 回滚分支；DB 前后对比。

### S9 RabbitMQ 异步消费（item.sale_count +1）

S6 下单成功后等 1~3 秒：

```powershell
Start-Sleep -Seconds 3
& $mysql --host=127.0.0.1 --port=3306 --user=ershou_item --password=$env:MYSQL_PASSWORD --database=ershou_item -N -B -e "SELECT id,sale_count FROM item WHERE id=$ITEM_ID;"
```

**预期**：`sale_count` 比下单前 +1（= 本次 quantity）。
可选队列证据（RabbitMQ 管理 API）：

```powershell
$b64=[Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))
(Invoke-WebRequest 'http://127.0.0.1:15672/api/queues/%2F/ershou.item.order-created.queue' -Headers @{Authorization="Basic $b64"} -UseBasicParsing).Content
# 关注 consumers（>=1）/ messages_acknowledged 增长 / messages（积压）
```

**链路**：order-service `@TransactionalEventListener(AFTER_COMMIT)` 发 `ershou.order.topic / order.created` → `ershou.item.order-created.queue` → item-service `@RabbitListener` 执行 `UPDATE item SET sale_count = sale_count + ?`（Redis db2 `ershou:item:mq:order:{orderId}` SETNX 防重复消费）。

### S10 Gateway Sentinel 限流（QPS=5 → 429）

并发 10 次打商品列表（PowerShell）：

```powershell
$client = New-Object System.Net.Http.HttpClient
$url = "$G/items?pageNum=1&pageSize=10"
$tasks = @(); 1..10 | ForEach-Object { $tasks += $client.GetAsync($url) }
foreach ($t in $tasks) { [void]$t.Wait(8000) }
$tasks | ForEach-Object { [int]$_.Result.StatusCode }
```

**预期**：状态码里**至少出现一个 `429`**，响应体 `{"code":429,"message":"请求过于频繁，请稍后重试"}`。
（规则：`/api/v1/items` QPS=5，代码内置 + Sentinel Dashboard `http://127.0.0.1:8858` 可看资源/规则）
> 注意：QPS=5 是**故意配得很低**用于演示；手动点页面几乎不会触发，只有并发压测才会 429。

### S11 未迁移路径经网关反代旧单体（功能不中断）

```powershell
curl.exe -s "$G/platform/shop/points?campusId=2"
```

**预期**：`code=0`，`data` 是点位数组（旧单体 platform 模块提供，证明绞杀者反代生效）。
> 该路径属于「其余 `/api/v1/**` → `http://127.0.0.1:8080`」路由。若旧单体没起，网关会返回 502/503。

---

## 5. 结果文件与证据

一键脚本产出：

| 产物 | 路径 | 内容 |
| --- | --- | --- |
| 汇总 JSON | `.build/cloud/demo-result.json` | meta（网关/手机号/时间/主机）、preflight（网关/旧单体/MySQL 三库探测）、summary（step 级 PASS/FAIL/SKIP + 校验数）、11 步明细（每步 expect/actual/真实响应片段） |
| 旧单体日志 | `.build/cloud/logs/legacy-*.out.log` / `.err.log` | 脚本自动拉起旧单体时的 stdout/stderr |
| 中间件日志 | `.build/cloud/logs/cloud-up-*.log/json` | 第 1 步 cloud-up 的验收证据 |
| 迁移日志 | `.build/cloud/logs/migrate-data-*.log` | 第 2 步逐表行数对比 |
| 服务日志 | 各服务控制台 / `.build/cloud/logs/*-service.log` | 启动、Seata Rollback、MQ 消费、Sentinel 等 |

**PASS 判定**：

- 脚本退出码 `0` 且 `summary.fail = 0`（`SKIP` 不算失败，但演示时应避免 SKIP）。
- `summary.checksFailed = 0`。
- 11 步每步 `status = PASS`，且 S6/S8/S9 的 DB 断言为 PASS（这是 Seata/RabbitMQ 的关键证据）。

**截图建议**（配合设计 §七.3）：`/nacos` 服务列表、Sentinel Dashboard 的 `/api/v1/items` 资源与 QPS=5 规则、RabbitMQ 管理台队列消费曲线、`.build/cloud/demo-result.json` 汇总表、Seata 回滚前后的 DB 对比。

---

## 6. 故障排查

| 症状 | 最可能原因 | 排查 / 处理 |
| --- | --- | --- |
| `cloud-demo.ps1` 退出码 **2**（网关未监听） | 4 个服务没起 / 网关崩了 | `Get-NetTCPConnection -LocalPort 8088`；看 gateway 控制台；Nacos 服务列表 count<4 说明还有人没注册 |
| 网关 503 `Service Unavailable` | 目标服务未注册到 Nacos（或已下线） | `http://127.0.0.1:8848/nacos` 看服务实例；服务日志看 nacos 注册报错；等 10~30s 重试 |
| 网关 502 / 反代失败（S11 失败） | 旧单体 8080 没起 | 起旧单体（第 4 步）；`curl http://127.0.0.1:8080/api/v1/categories` |
| S3 返回 401 / `code=10002` | token 没带 / 已失效 / 网关与 user-service 的 Redis db 不一致 | 检查请求头 `Authorization: Bearer <token>`；确认 `ershou-gateway.yaml` 与 `ershou-user-service.yaml` 的 `sa-token.redis.database=0` |
| S4 出现 429 | **Sentinel 限流生效（这是正常的）** | 脚本 S4 只发 1 次不会 429；若手动连续刷新出现 429，稍等 1s 再试 |
| S6 下单失败 `ITEM_LOCKED` / 库存不足 | 挑到的商品 stock=0 或已被锁 | 换一个 `status=2 AND stock>0` 的商品；`SELECT id,stock FROM item WHERE status=2 AND stock>0` |
| S6 失败且日志有 `Feign` 异常 | item-service 未注册 / 内部接口 404 / Sentinel fallback 触发 | 看 order-service 日志的 Feign 目标；确认 item-service 的 `/inner/items/{id}` 存在；确认 item 服务已注册 |
| S8 居然成功了（没有回滚） | `debugFail` 未透传到 order-service，或 `@GlobalTransactional` 没生效 | 看 order-service 日志是否进入 `debugFail` 分支；确认 Seata TC 容器 healthy、order/item 的 Nacos 配置 `seata.enabled=true` 且 `undo_log` 表存在 |
| S8 报 `Seata can not get TC` / 连接超时 | Seata Server 不可用或 `vgroup-mapping` 错 | `docker ps`/Seata 控制台 7091；核对设计 §5.1 的 registry/vgroup-mapping（`ershou_tx_group` → `default`） |
| S9 `sale_count` 不变 | 消费者没起 / 队列绑定缺失 / 消息进了死信 | RabbitMQ 管理台看队列 `ershou.item.order-created.queue` 有没有 consumers、messages 积压；item-service 日志找 `order-created`；检查 Redis db2 幂等键 |
| S10 没有 429 | Sentinel 规则没加载 / 并发不够 / 限流未生效 | 看 gateway 启动日志是否有 Sentinel 规则初始化；Dashboard 8858 看 `/api/v1/items` 资源阈值；重跑 2~3 轮（脚本已内置 3 轮重试） |
| 脚本把「DB 校验」标 SKIP | 找不到 mysql 客户端 / 三库账号未建 | 用 `-MySQLPath '完整路径'`；先执行第 2 步 init sql；`ershou_item`/`ershou_order` 账号密码见 `.env` 的 `MYSQL_PASSWORD` |
| 迁移日志末尾「新库表行数」全 0 | 迁移脚本汇总段统计口径问题（**数据已实际迁移**） | 以逐表 `[OK]` 段与第 2 步 SQL 直连为准；QA 复核先看逐表行数对比 |
| 中文乱码 / 脚本解析报错 | 脚本被保存成 UTF-8 无 BOM（PS 5.1 读中文会乱） | 用 VSCode 另存为 `UTF-8 with BOM`；本仓库脚本都应带 BOM |
| 重跑后库存耗尽 | 16 件在售商品每件 stock=1，每次演示消耗 1 件 | 重跑第 2 步 `migrate-data.ps1` 重灌（会 TRUNCATE 新库后重迁）；或 `UPDATE item SET stock=1 WHERE ...`；或换商品 |

---

## 7. 清理与重跑

```powershell
# 停中间件（保留既有 redis/mysql/es/minio 不动）
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1

# 清理演示产物（可选）
Remove-Item .\ershou-web\dist -Recurse -Force -ErrorAction SilentlyContinue
```

- **重跑安全**：`cloud-up.ps1` / `migrate-data.ps1` / `cloud-demo.ps1` 都是幂等或可重复执行的；
  `cloud-demo.ps1` 会持续创建演示订单（每次 S6 消耗 1 件库存），演示完可用迁移脚本重灌数据。
- 演示脚本的测试订单不会伪造金额/买家：都用真实链路（Sa-Token 登录 + Feign + Seata + MQ）。
- 演示前建议把 `.build/cloud/demo-result.json` 留档（脚本每次覆盖）。

---

## 8. 相关文档

| 文档 | 内容 |
| --- | --- |
| [`architecture.md`](./architecture.md) | 架构图（mermaid）、模块/端口/数据流说明 |
| [`migration-roadmap.md`](./migration-roadmap.md) | 剩余模块迁移计划（cart/favorites/chat/admin/... 下一轮） |
| `智能体团队/第5轮-SpringCloud重构-设计.md` | 本轮唯一契约：数据拆分、接口、Redis、Seata/RabbitMQ、验收标准 |
| `ershou-cloud/docker/README.md` | 中间件编排与自建 Sentinel 镜像说明 |
| `ershou-web/vite.config.ts` / `ershou-web/.env.cloud` | 前端接网关的代理配置（第 3 节） |
| `scripts/cloud-up.ps1` / `cloud-publish-config.ps1` / `cloud-down.ps1` | 中间件起停与 Nacos 配置发布 |
| `scripts/cloud-demo.ps1` | 本文第 4 节 11 步的自动化断言脚本 |
