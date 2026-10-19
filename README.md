# 校园二手交易平台

面向高校场景的二手交易信息撮合平台：**轻注册（不实名）+ 校内地图面交 + AI 内容审核**。
平台只做信息撮合与监管，**不介入交易资金**（面交线下自行支付）。

| 模块 | 目录 | 技术栈 |
| --- | --- | --- |
| 需求文档 | `校园二手交易平台需求文档.md` | PRD（含校内地图、AI 审核、毕业季代卖） |
| 开发文档 | `开发文档.md` | 架构、数据库、Redis、**Elasticsearch 搜索**、接口、部署、排期 |
| 开发日志 | `开发日志.md` | 每轮迭代的产出 / 决策 / 踩坑 / 实测验证（倒序，最新在最上面） |
| 环境清单 | `环境检查与安装清单.md` | 本机环境实测结果与安装步骤 |
| 后端 | `ershou-server/` | Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 + Sa-Token + MinIO + Elasticsearch 8 + WebSocket |
| 前端 | `ershou-web/` | Vue 3.5 + Vite 6 + TypeScript + Element Plus + Leaflet |
| 数据库 | `sql/schema.sql` | MySQL 8，20 张表 + 种子数据 |
| 依赖编排 | `docker-compose.yml` | Redis（6380）+ MinIO（9010/9011）+ Elasticsearch（9211），手动启动 |

---

## 一、本地环境（已实测就绪）

| 组件 | 地址 | 说明 |
| --- | --- | --- |
| MySQL | 127.0.0.1:3306 | 服务名 **MySQL**（不是 MySQL80，那个是 3308），库 `ershou`，账号 `ershou` |
| Redis | 127.0.0.1:**6380** | 容器 `ershou-redis`，避开青光眼项目的 6379 |
| MinIO | API **9010** / 控制台 **9011** | 容器 `ershou-minio`，桶 `ershou` 由后端启动时自动创建 |
| Elasticsearch | http://127.0.0.1:**9211** | 容器 `ershou-es`（容器内仍是 9200），商品搜索索引 `ershou_item`（后端启动自动建索引+全量同步） |
| 后端 | http://127.0.0.1:8080 | 接口文档 /swagger-ui.html |
| 前端 | http://localhost:5173 | /api 代理到 8080 |

**所有密码集中在根目录 `.env`（已 gitignore）**，`.env.example` 是模板。

> ⚠️ **ES 为什么是 9211 而不是 9210**（2026-10-10 排查出来的环境冲突）：
> `[::]:9210` 被 WSL 里的 `wsl-bootstrap` 占着，Windows 侧 `127.0.0.1:9210` 还被 QQ 占了。
> 结果是访问 9210 拿到的是**别的服务**的报错（一个 JWT 错误），
> **真实 ES 从头到尾没被访问到** → `POST /search/reindex` 稳定报 `90001`，
> 看起来像代码 bug，其实代码完全正常。换到 9211（已确认两侧空闲）后恢复正常。
> 以后遇到"某个接口稳定报通用错误、错误信息毫无指向性"，先核实**依赖服务是否真在它该在的端口上**。

## 一·五、手机端（Flutter）局域网联调

| 位置 | 说明 |
| --- | --- |
| `ershou_app/` | Flutter Android 客户端（详见 `ershou_app/README.md`） |
| `scripts/lan-info.ps1` | 打印手机该连的 IP + 自测 8080，换网络后跑一下 |
| `scripts/mobile-connect.ps1` | **推荐**：用 USB 数据线建 adb reverse 隧道（见下），不怕 IP 变、不要求同网段 |
| `LanDiscoveryResponder` | 后端 UDP **48899** 应答，手机「一键扫描」靠它秒连（**需重启后端生效**） |

```powershell
# 方式一（推荐，USB 连着就行）：手机 ↔ 电脑建隧道，绕开网段问题
powershell -ExecutionPolicy Bypass -File .\scripts\mobile-connect.ps1

# 方式二（WiFi 直连）：先看该连哪个 IP，再在 App 里手动填
powershell -ExecutionPolicy Bypass -File .\scripts\lan-info.ps1
```

App 里提供三条连接路径：**一键扫描**（UDP 广播 → 网段扫描 → **USB 隧道**）、**手动输入 IP:8080**、**扫二维码**；
冷启动会先探测上次用过的地址，能用就直接进登录页。

### 为什么推荐 USB 隧道（`scripts/mobile-connect.ps1`）

**校园网 / 热点一换，电脑 IP 就变，手机里存的旧地址立刻失效**。2026-10-10 实际踩到：
手机在 `192.168.236.0/24`、电脑 WLAN 在 `10.33.56.248`（**不同网段，互相 ping 不通**），
App 里存的还是 `192.168.236.112` —— 打开地图就报错。用 adb reverse 走 USB 就完全绕开这件事：

```powershell
adb reverse tcp:8080 tcp:8080   # 后端 API
adb reverse tcp:9010 tcp:9010   # MinIO 图片（⚠️ 不转发商品图会全部加载失败）
adb reverse tcp:9011 tcp:9011   # MinIO 控制台
```

之后 App 里填 **`127.0.0.1:8080`**（连接页「一键扫描」也会识别成「USB 隧道」）。
脚本把三条隧道 + 手机侧实测 + "是否同网段"的判断都做完了。

> ⚠️ **拔掉 USB、重启 adb 或重启电脑后隧道会失效**，重新跑一次脚本即可。

### 地图打不开时，先看它到底在说什么

这条曾经把我坑了好几轮：**"后端还没配置高德 key" 这句话原先是错的**。

`map_page.dart` 当时只有 `_amapReady` 一个布尔量，而 `MapRepository.amapConfigured()` 是
`catch (_) { return false; }` —— **网络失败被吞掉，和"真的没配 key"返回同一个 false**。
于是"手机连不上后端"被显示成"后端没配 key"，误导人去 `.env` 里反复折腾（后端其实配好了）。

现在改成**三态** `AmapKeyState { configured, missingKey, unreachable }`，提示按真实原因给：

| 状态 | 提示 |
| --- | --- |
| `missingKey` | "后端还没配置高德 key……在后端 .env 里补 `AMAP_JS_KEY`" |
| `unreachable` | "**连不上后端**，请确认后端已启动、App 里的服务器地址正确（USB 调试可跑 `mobile-connect.ps1`）" |

**排查顺序**：① 后端起了吗（`GET /api/v1/map/amap-status` 的 `configured`）→ ② 手机能连到后端吗（地址/网段/隧道）→ ③ 才是 key。

### 后端用 IDEA 启动就够（不必用脚本）

**IDEA 直接 Run `CampusApplication` 是完全支持的**，`.env` 会自动加载 ——
`DotenvEnvironmentPostProcessor` 按候选路径找 `.env`（工作目录、上一级、上上级，
也认 `ERSHOU_ENV_FILE` 环境变量），IDEA 的工作目录是 `ershou-server`，正好命中"上一级"。
实测：IDEA 起的实例 `amap-status` → `configured: true`、`amap-html` 12092 字节（真地图页）。

> ⚠️ **端口 8080 只能有一个后端**。用 IDEA 起了之后，别再跑 `dev-server.ps1`（会抢端口失败），
> 反过来也一样。改完代码 IDEA 里点重启即可，不需要切到脚本。


### 登录态：一次登录长期有效（不用反复登录）

**设计目标是"登录一次之后一直保持，除非用户自己点退出登录"。** 三段链路都要成立：

| 环节 | 实现 | 怎么自查 |
| --- | --- | --- |
| 客户端保存 | token 落 SharedPreferences（`ershou.token`） | `adb shell "run-as com.ershou.ershou_app cat /data/data/com.ershou.ershou_app/shared_prefs/FlutterSharedPreferences.xml"` |
| 冷启动恢复 | `AuthNotifier.build` 从本地读回 token；`apiClientProvider` 建 client 时装上它 | 冷启动应直接进首页，**不出现登录页** |
| 服务端会话不丢 | Sa-Token 会话存 **Redis**（`Authorization:login:token:*`），后端重启不会清空 | 重启后端后同一个 token 仍能调 `/users/me` |

**会话有效期 = 10 年**（`application.yml` 的 `sa-token.timeout: 315360000`，`active-timeout: -1`）：

- 原来是 7 天 —— 这是**真实会掉登录**的坑：学生放假超过 7 天没打开 App，回来就得重新登录，
  而这并不是"用户自己退出"；
- 校园二手没有理由让会话过期。真要作废凭证有两条路：用户点**退出登录**，
  或服务端删掉 Redis 里的会话 key；
- `active-timeout: -1` 也必须一起设：否则"闲置超过阈值"一样会判失效，前面那个 10 年形同虚设。

> ⚠️ **开发期最容易被误判成"登录坏了"的两件事**：
> 1. `flutter install` 会**先卸载再安装**（日志里写着 `Uninstalling old version...`），
>    Android 卸载会清空 App 数据 —— 登录态没了。**这不是 bug，是重装**。
>    反复装 App 的迭代期，每次都要重新登录一次；
> 2. 换局域网 IP 后，App 里存的地址变了要重连一次；token 与地址无关（跟着账号），
>    所以重连不会要求重新登录。

> ⚠️ **改 `sa-token.timeout` 不会给已有会话续期**：Sa-Token 的 TTL 在**签发时**就写进 Redis 了，
> 改配置只影响之后新登录的 token。所以想让某台设备吃到新的有效期，**退出登录再登一次**。
> 自查当前有效期：`docker exec ershou-redis redis-cli -a <密码> --no-auth-warning TTL Authorization:login:token:<token>`

## 二、启动步骤

```powershell
# 1) 启动依赖容器（手动，restart=no 不会自启）
.\scripts\dev-up.ps1

# 2) 启动后端（自动使用 IDEA 自带 Maven + JDK 21）
.\scripts\dev-server.ps1

# 3) 启动前端
.\scripts\dev-web.ps1

# 4) 端到端冒烟测试（另开一个终端）
.\scripts\smoke-test.ps1
```

**一键启动前后端**（依赖自检 + 前后端各开一个窗口 + 等到就绪），等价于上面 1~3 步：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-all.ps1
# 可选：-TimeoutSec 300（后端首次拉依赖慢）/ -BackendOnly / -FrontendOnly / -NoWait / -SkipDeps
```

> 端口已在监听时不会重复启动（幂等），可直接重复执行。前端务必用 **localhost** 访问，
> `127.0.0.1:5173` 连不上（vite 只绑了 IPv6 回环）。

其他脚本：`dev-down.ps1`（停依赖）、`check-env.ps1`（环境自检）。

> ⚠️ **`smoke-test.ps1` 是"直接删库"式测试**：它每次运行都会清空
> `item / meetup_order / cart_item / favorite / credit_log` 五张表（不管是谁的数据），
> 只保留 `sys_user` 与 `trade_point`。
> 现在它会先列出受影响行数并要求输入 `yes` 确认；无人值守/CI 用 `-Force` 跳过。
> **要保留库里的数据时先备份**：
> ```powershell
> mysqldump -h 127.0.0.1 -u root -p ershou > .build\backup-$(Get-Date -f yyyyMMdd-HHmm).sql
> ```
> 想重新灌演示数据：`powershell -File .build\seed-demo.ps1`（9 件商品 / 3 个卖家）。

> ES 镜像默认由 `docker/es/Dockerfile` 构建，已内置 **IK 中文分词**（装不上会自动退官方 smartcn，再不行用内置 ngram —— 后端启动时探测实际可用的分词器再建索引）。
> 想用纯官方镜像：删掉 `docker-compose.yml` 里 elasticsearch 的 `build` 段、把 image 改回 `elasticsearch:8.13.4`，然后删除旧索引重启后端即可。

### 开发期关闭商品限时（2026-10-18，仅开发/演示）

开发/演示期商品太少时，可以**关闭商品限时下架**并把历史已过期商品重新上架。生产请保持默认开启。

```powershell
# 1) 本地 .env（已被 gitignore）默认已配：ITEM_VALIDITY_ENABLED=false、ITEM_VALID_DAYS=36500
#    生产/正式数据：改回 ITEM_VALIDITY_ENABLED=true（或不配，走默认 true）
# 2) 把历史「已过期(7)」商品重新上架（幂等；只动 status=7，不动已售出/已下架/锁定）
Get-Content .\sql\dev-relist-expired.sql -Raw | mysql --host=127.0.0.1 --port=3306 --user=ershou --password=<你的密码> --database=ershou
# 3) 重启后端生效（配置在启动时读取）
```

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `ershou.item.validity-enabled`（env `ITEM_VALIDITY_ENABLED`） | `true` | `false` 时发布/重新上架不写 `expire_at`（NULL），过期定时任务直接跳过 |
| `ershou.item.valid-days`（env `ITEM_VALID_DAYS`） | `7` | 商品有效期（天），置超大值可作双保险 |
| `ershou.item.expire-cron`（env `ITEM_EXPIRE_CRON`） | `0 30 * * * ?` | 过期扫描 cron |

### 让「高数」能搜到「高等数学」（简称 / 同义词）

两个文件都在 `docker/es/`，改完重建镜像即可：

| 文件 | 作用 | 什么时候改 |
| --- | --- | --- |
| `custom.dic` | IK 扩展词典：让简称/校园词被切成**一个词**（高数、电驴、一卡通、洗衣机…） | 出现一个**新词**时（不改这里，同义词规则不会触发） |
| `synonyms.txt` | 查询期同义词表：`高数,高等数学`，同行内双向生效 | 给已有词建立等价关系 |

```powershell
# 1) 改 docker/es/custom.dic 或 docker/es/synonyms.txt
# 2) 重建镜像并重启容器
docker compose up -d --build elasticsearch

# 3a) 只改了 synonyms.txt，想不重建容器就生效：
docker cp docker/es/synonyms.txt ershou-es:/usr/share/elasticsearch/config/analysis-ik/synonyms.txt
Invoke-WebRequest -Method Post http://127.0.0.1:9211/ershou_item/_reload_search_analyzers

# 3b) 改了 custom.dic（新增词）：必须重建索引，文档切分才会用上新词
Invoke-WebRequest -Method Delete http://127.0.0.1:9211/ershou_item   # 重启后端会自动重建
```

**核对索引一致性时别踩这个坑**：索引里存的是**所有状态**的商品（设计如此：状态流转只 upsert 不删文档，
公开检索固定过滤 `status=2`），所以**不能**拿 `_count` 直接和 `WHERE status=2` 比 —— 必然不等，
会被误判成"索引不一致"。两个口径要分别对：

```powershell
# 全部（docCount）
curl.exe "http://127.0.0.1:9211/ershou_item/_count"                  # ↔ SELECT COUNT(*) FROM item
# 在售（visible，即用户实际能看到几件）
curl.exe "http://127.0.0.1:9211/ershou_item/_count?q=status:2"       # ↔ ... WHERE status=2
# 重建接口同时返回这两个数：{"indexed":12,"visible":10,"docCount":12,...}
```

> 两条硬性规则（都是踩过的坑）：① 同义词每个条目必须是**一个词** —— 简称要先写进 `custom.dic`，否则 IK 把「高数」切成「高|数」，规则永远不命中；② 不要写多词短语（如「平板电脑」「移动电源」），同义词的值会再走一遍分词，把子词带进查询造成过度扩展（搜「平板」会连带搜到「电脑」）。

## 三、已实现功能

### 后端（216 个 Java 文件 / 20 个 Controller）

> 数字按 **2026-10-10 实测**更新（`GET /v3/api-docs` 里的操作数）；
> 这类统计会随改动过期，别当承诺看，以代码为准。

| 模块 | 能力 |
| --- | --- |
| 认证 | 手机号 + 验证码登录（轻注册，**无实名/学籍字段**）；手机号 AES 加密存储 + SHA256 索引；新号 7 天内限额；**个人资料编辑**（`PATCH /users/me`：昵称/头像/学校校区/小铺名与小铺简介，昵称与小铺名过内容审核，头像只收上传接口产出的地址） |
| 商品 | 发布（含 AI 机审）/ 详情 / 列表 / 搜索筛选排序 / 下架 / 我的发布 |
| **搜索** | Elasticsearch 8 索引 `ershou_item`：中文分词（IK→smartcn→ngram 自动探测）、`title^3` 相关性排序、`<em>` 高亮；ES 挂掉或深分页自动降级 MySQL LIKE；索引随写路径实时同步 + 2 分钟增量兜底 + 管理员全量重建（**清空重建**，会删掉索引里多余的幽灵文档） |

**列表一次加载多少条**（2026-10-02 实测）
| 端 | 机制 | 一次几条 | 备注 |
| --- | --- | --- | --- |
| 网页端首页 | **分页**（页码 + 跳页），不是懒加载 | **16 条** | 55 件 → 4 页（16/16/16/7） |
| 移动端首页 | **上拉加载更多**（距底部 420px 自动触发 `loadMore`） | **20 条** | 55 件 → 上拉 3 次（20/20/15） |
| 后端上限 | `Math.min(pageSize, 100)` | 传 1000 也只给 100 | 见 `ItemService` |

图片是懒加载的：网页端 `el-image lazy`，移动端 `CachedNetworkImage` + 按显示尺寸解码
（列表缩略图不会把 3000px 原图整张读进内存）；移动端列表本身由 `CustomScrollView` 按需构建。

> ⚠️ **ES 索引会脏，而且 `reindexAll()` 不会帮你清理**（踩过）：
> 它是 **upsert 语义**，只覆盖不删除 —— 直接用 SQL 物理删商品（测试脚本这么干过很多次）
> 时写路径走不到，索引里的文档就永久留下来了。实测积累到 `ES 311 条 vs MySQL 在售 10 条`，
> 后果是**搜索结果 `total` 虚高**：搜「自行车」显示"共 12 件"但一条都列不出来
> （total 来自 ES、records 回库后被 `status != 2` 筛掉）。
> 处置：**删索引再重启后端**（`reindexOnStartup=true` 会按当前库重建），
> 或调 `POST /api/v1/search/reindex`（注意：它同样只做 upsert，清不掉幽灵文档）。
> 正常通过接口下架/售出的商品不受影响 —— 那条路径会 `syncById` 覆盖成新状态。
| **AI 审核** | 禁售词直接拦截（`50001`）、可疑词转人工（状态"审核中"）；接口抽象，可换 Ollama 或云审核 |
| **校内地图** | 底图配置 + 点位（双坐标）+ 每个点位在售数/最低价 + 面交点推荐 + 用户自定义点位提交 |
| 收藏 | 收藏/取消（唯一索引兜底并发重复） |
| 购物车 | 按卖家分组、增删改、**合并面交结算**（同卖家合并、共享行程号 batchNo） |
| 面交单 | **约面交 = 意向单**（提交不锁库；面交点/时间窗都可留空 = 待协商，提交后自动私聊提醒卖家）→ 卖家接受（这时才条件更新锁库，锁到时间窗结束 +2h）→ 见面验货 → 卖家点「已卖出」成交（商品售出 + 双方信用分 +2）；核销码已删除 |
| **私聊 IM** | WebSocket `/ws/chat`（Sa-Token 握手鉴权）+ REST 兜底：从商品发起会话（唯一索引复用）、文本/图片消息、未读数与已读回执、快捷话术、对方在线状态；**买卖角色区分**（会话列表显示"我买/我卖"，气泡左侧对方头像+「昵称（卖家）」，顶部标明"我是买家/卖家"）；**消息撤回**（仅发送方、限时 2 分钟、双向实时消失、会话预览同步改「已撤回」）；**删除聊天记录**（每用户软删除：只从我的列表移除、未读一起归零，对方不受影响，对方再发消息自动恢复；支持**多选批量删除**）；**拉黑**（单向存储双向生效：任一方拉黑则双方都发不出，会话与主页都有状态提示）；**用户主页**（`/user/{id}`：信用/学校/在售，可私聊可拉黑，公开接口不含任何联系方式）；**聊天风控**：高危词拦截并留痕（独立事务，证据不随发送失败回滚）、可疑词弱提醒不篡改原文、新号每日私聊限额；正文 AES-GCM 加密落库；跨实例 Redis Pub/Sub 广播 |
| 文件 | MinIO 上传（校验 → 压缩长边 1280 → 存桶），MinIO 不可用时可切 `local` 降级 |
| 定时任务 | 释放超时锁库、面交单 2 小时超时失效、商品 **7 天**到期自动下架（并作废该商品上的挂单）、**浏览量每 10 分钟批量回写** |
| **运营后台** | 审核工作台（商品 / 点位 通过·驳回·批量）、**上架审核**（实时确认每条上架都被看过）、模板化驳回原因、商品全状态检索与**强制下架**、用户封禁/解封/限权、信用分人工调整（写 credit_log 流水）、操作审计（sys_audit_log，含变更前后与 IP）；管理员判定统一走 role_flags & 8 或白名单 |
| **平台代卖（官方严选）** | **平台作为卖家**：官方账号 `id=1000`（`role_flags=16` 新位、不可登录）+ `item.seller_type=2` 标注；后台**代卖/自提点管理**（CRUD / 启停 / 软删 / 排序）；`/platform` 专区页：代卖点筛选 + **秒杀**（Lua 原子扣减 + Hash 限购 + 5s 限流 + 启动预热/重建 + 失败补偿）+ **优惠券**（Lua 抢券 + 每人限领 + DB 唯一键兜底）+ 平台商品直购（**条件扣库存、不锁库**，并发不超卖）；平台单复用面交单状态机（平台自动确认 → 买家「我已收到」完成 → 取消回补库存），买家信用 +2、平台账号不计分；缓存 4 类（代卖点/券/场次/概览）TTL 为设计上限×90% 防抖动 |
| **Redis 读缓存** | 商品详情（10 分钟 + 空值缓存防穿透，**走 `getOrLoad` 重建锁防击穿**）、地图点位含在售数/最低价（30 分钟，版本号 O(1) 整体失效）、分类/学校/校区/点位字典（1 小时，MGET 消 N+1）、运营概览 8 个 count（30 秒）；所有 TTL 加 **0~10% 抖动**防雪崩；浏览量走 Redis 计数 + 每 10 分钟批量回写；失效统一在 `TxHooks.afterCommit` 执行；`ershou.cache.enabled=false` 一键回滚 |
| **平台运营台（独立控制台）** | 独立全屏 Shell（`/console/platform`，248px 深色侧栏 + 64px sticky 顶栏 + 通栏 max-width 1760，**不套商店/运营后台布局**）：概览 Dashboard（6 KPI 大卡 + 最近订单 + 库存告警/场次倒计时/快捷操作）、平台商品管理（筛选/发布/编辑/下架/重新上架）、代卖点、优惠券（增改/启停/领取进度）、秒杀场次（场次 + 商品增删 + 状态切换）、平台订单（tabs/搜索/抽屉详情）、缓存运维（Redis key/TTL/值 + 重建 + 自动刷新）；后端 `PlatformConsoleController` 18 个管理接口（每接口 `requireAdminId()`），商品写后同步 ES，券/场次写后只重建对应 Redis 缓存 |
| 通用 | 统一响应体 + 错误码分段 + 全局异常 + traceId 链路 + 分页 + Swagger |

### 前端（30 个页面 / 8 个业务组件 / 77 个 ts+vue 源文件）

> 数字按 **2026-10-10 实测**更新（`src/views/*.vue` 21 个、`src/components/*.vue` 8 个）。

登录、首页（搜索/分类/排序/分页 + 商品卡片**浏览量徽标**）、**校内地图**（双模式：`map_type=1` Leaflet CRS.Simple 自绘平面图 / `map_type=2` **高德 JS API 真地图 + 定位蓝点 + 米制距离**）、商品详情、发布（多图上传）、购物车（分组 + 合并面交）、我的面交单（买家/卖家双 tab、核销）、我的收藏、我的发布、**商品详情（约面交直接下单：选面交点 + 时间窗，面交点下拉可输入搜索）**、**个人中心（含编辑资料：点头像或按钮打开弹窗，改昵称/头像/学校校区/小铺，头像走 MinIO 单图上传）**、**消息（私聊：会话列表 + 聊天窗 + 快捷话术 + 未读红点，断线指数退避重连）**。

**运营后台**（/admin，仅运营可见，路由 requiresAdmin 守卫）：运营概览、审核工作台（商品/点位双 tab + 批量处置 + 驳回原因模板）、商品管理（强制下架）、用户管理（封禁/解封/信用分）、操作日志、**平台代卖点管理**（第 2 轮新增）。

**平台严选**（/platform，第 2 轮新增，游客可浏览）：官方严选 hero（在售平台商品 / 代卖点 / 可领券统计）+ 代卖点 chips 筛选 + **限时秒杀**（倒计时 / 库存进度 / 四种状态）+ **优惠券**（满减/折扣、剩余量、领取与已领态）+ 平台商品分页（复用商品卡，显示「官方代卖」金色角标）；首页与顶部导航均有入口，商品详情页有官方徽章与专区回链。领券/抢购/购买未登录统一跳 `/login?redirect=/platform`。

**平台运营台**（/console/platform，第 3 轮新增，仅运营可见）：**独立全屏控制台**（深色 248px 侧栏 + 64px 顶栏 + 通栏内容，不套商店布局）7 个页面 —— 概览（6 KPI + 最近订单 + 库存告警/倒计时/快捷操作）/ 平台商品（发布、编辑、下架、重新上架）/ 代卖点 / 优惠券 / 秒杀场次 / 平台订单 / 缓存运维（Redis key 与 TTL 可视化 + 一键重建）。运营后台头部有「平台运营台 →」入口，旧地址 `/admin/platform-points` 自动跳转。

**移动端平台严选**（Flutter App，第 4 轮新增）：`ershou_app/lib/ui/pages/platform_page.dart` —— Hero 统计卡（在售平台商品/代卖点/可领券）+ 代卖点 chips 筛选 + 限时秒杀（每秒倒计时/秒杀价/库存进度/抢购四态）+ 优惠券领取 + 严选好物 2 列网格（分页/下拉刷新/空态/错误重试）；首页顶栏金色「严选」入口，商品卡显示金色「官方代卖」角标，详情页有官方徽章。数据层为 `platform_models.dart` + `platform_repository.dart`，复用第 2 轮后端接口；已有真机（KOZ-AL00）实测截图。

## 三·五、Spring Cloud 微服务架构（演示版，2026-10-19）

> 工程目录：`ershou-cloud/` ｜ 启动手册：[`ershou-cloud/README.md`](ershou-cloud/README.md) ｜ 架构图：[`docs/architecture.md`](ershou-cloud/docs/architecture.md) ｜ 演示剧本：[`docs/demo-script.md`](ershou-cloud/docs/demo-script.md) ｜ 迁移路线：[`docs/migration-roadmap.md`](ershou-cloud/docs/migration-roadmap.md) ｜ 验收报告：[`第5轮-SpringCloud重构-验收.md`](智能体团队/第5轮-SpringCloud重构-验收.md)

采用**绞杀者模式**：旧单体 `ershou-server` 继续在 **8080** 运行并承载未迁移模块；新微服务从核心链路逐步接管，统一入口为 **Gateway 8088**，**前端无需改代码**（Web 改代理端口、App 填 `host:8088`）。

| 服务/中间件 | 端口 | 说明 |
| --- | --- | --- |
| `ershou-gateway` | **8088** | Spring Cloud Gateway：Sa-Token 鉴权（共享 Redis db0）+ Sentinel 限流 + 路由/反代 |
| `ershou-user-service` | 8081 | 认证/用户/学校校区（MySQL `ershou_user`、Redis db1） |
| `ershou-item-service` | 8082 | 商品读/缓存/内部库存/AMQP 消费（MySQL `ershou_item`、Redis db2） |
| `ershou-order-service` | 8083 | 面交单 + OpenFeign + **Seata AT** + MQ 生产（MySQL `ershou_order`、Redis db3） |
| 旧单体 `ershou-server` | 8080 | 未迁移模块（chat/admin/map/platform/search/cart 等），由网关反代 |
| Nacos / RabbitMQ / Seata / Sentinel | 8848 / 5672·15672 / 8091·7091 / 8858 | `ershou-cloud/docker/docker-compose.yml`（Docker，`scripts/cloud-up.ps1` 一键起） |

**一键演示**：
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1          # 中间件 + Nacos 配置 + 冒烟
powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1        # 经网关 11 步端到端断言
cd ershou-web; npm run dev -- --mode cloud                               # 前端切到 8088
```
**实测结果**：11/11 步 PASS（登录→用户→商品→下单→我的面单→**Seata 回滚**→**RabbitMQ 异步**→**Sentinel 429**→旧路径反代），MySQL 三库独立账号、Redis 按 db 隔离（db0 仅会话）、Nacos 配置热生效。

## 四、关键验证结果

### 平台代卖（2026-10-17 独立验收，`智能体团队/第2轮-平台代卖-验收.md`）

| 项 | 结果 |
| --- | --- |
| 后端编译 `mvn -DskipTests compile` / Web `npm run build` | **exit 0 / exit 0** |
| DB（迁移 4 遍幂等 + 6 张表 + 种子 + 唯一键 + 双平面对账） | **64 PASS / 0 FAIL** |
| 运行时 API（公开读、领券 10 并发、秒杀 5 并发防超卖、直购/完成/取消回补、后台 CRUD、越权负向、8 项回归） | **132 PASS / 0 FAIL / 1 SKIP** |
| Redis（13 个 key、库存非负、DB↔Redis 对账 6/6、验收前后 diff 全部为负） | **36 PASS / 0 FAIL** |
| Web 页面（`/platform`、首页入口与角标、后台代卖点，Chrome 无 console error） | **30 PASS / 0 FAIL + 6 张截图** |

> 并发口径实测：**库存 5 → 5 个不同用户并发抢购全部成功、第 6 个失败（code=0 表示业务失败），Redis 库存归 0 且不为负、无重复落库**；平台单 `lock_user_id/lock_expire_at` 恒 NULL（不锁库设计）。
> 截图在 `.build/qa/web-*.png`；证据日志在 `.build/qa/logs/`；验收脚本为 `scripts/verify-platform-{db,api,redis,web}.*`，可重复执行。

### 平台运营台（2026-10-18 独立验收，`智能体团队/第3轮-平台运营台-验收.md`）

| 项 | 结果 |
| --- | --- |
| 后端编译 `mvn -DskipTests compile` / Web `npm run build` | **BUILD SUCCESS / exit 0** |
| 管理接口与写后副作用（18 接口 + 权限负向 36 项 + 改价/下架/券/场次即时生效） | **102 PASS / 0 FAIL / 1 WARN(信息项)** |
| Web 页面（7 页 × 1440 + 概览 1920 + 兼容 3 项 + 无 console.error） | **115 PASS / 0 FAIL / 1 SKIP（秒杀页卡片式无表格）** |
| 视觉量化（1440×900 全 7 页一致） | 侧栏 **248px** / 顶栏 **64px** / 内容宽 **1192 = 视口−248**（通栏）/ 表格横向溢出 **0** / KPI **6** / `wrapped=false`（独立 Shell） |
| 视觉量化（1920×1080 概览） | 内容宽 **1672 > 1600** / 横向溢出 0 / KPI 6 |
| 兼容 | 旧地址 `/admin/platform-points` → `/console/platform/points` 跳转通过；`/admin` 其他页、`/platform` 商城页正常 |

> 缺陷闭环 3 项：① overview/cache 顶栏被 flex-shrink 压到 43px → `.pc__top{flex:none}` 修复后 64px；② Items 页缺约定根类 `.console-page` → 已补；③ 同 ①的 cache 页。
> 截图在 `.build/console/*.png`（7×1440 + 1×1920 + 3 兼容）；脚本 `scripts/verify-console-{api.ps1,web.mjs}`。
> **环境已知问题**：`127.0.0.1:9211` 当前被非 ES 服务占用（返回 JWT 错误），ES 写路径降级 MySQL LIKE；运营台功能不依赖 ES，验收仍全 PASS。

### 移动端平台严选 + 开发期取消商品限时（2026-10-18 独立验收）

| 项 | 结果 |
| --- | --- |
| DB 数据修复（13 件过期商品重新上架，SQL 幂等两跑） | 在售 **3 → 16 件**、已过期 0 件、占位 `expire_at IS NULL` 17/17 |
| 商品限时关闭（配置 + 任务跳过） | 日志「商品限时已关闭…跳过本轮」40s 内 4 次；「件商品过期自动下架」**0 次**；测试发布/重上架商品 `expireAt=null`（API+DB 双证） |
| Flutter 编译测试 | `flutter analyze` **0 error / 0 warning**、`flutter test` **51/51**、`flutter build apk --debug` exit 0（207MB） |
| 真机（KOZ-AL00） | 首页「严选」入口 + 金色「官方代卖」角标；平台严选页 Hero/代卖点/限时秒杀/优惠券/严选好物全部截图与 UI 文本命中 |
| 端到端验收 | **108 PASS / 0 FAIL / 1 WARN**（脚本 `scripts/verify-mobile-round4.ps1`，报告 `智能体团队/第4轮-移动端严选与开发期限时-验收.md`） |
| Web 回归 | `npm run build` exit 0；兼容 `expireAt=null`（修掉「自动下架」无天数文案） |

> 验收期间用户的 IDEA 后端实例（8080）全程未被 kill/未被打扰；QA 用独立端口 18080 验收，跑完已释放。

```
违规内容"管制刀具"        → 50001 内容涉嫌违规：管制刀具（禁售品类）
可疑内容"加微信转账"      → 转人工，商品状态=审核中
购物车 2 件合并面交        → 2 张面交单共享同一 batchNo
核销码错误 / 重复使用      → 40004
双向确认完成              → 商品已售出 + 双方信用分 100 → 102
越权下架他人商品          → 30005
未登录访问需登录接口      → 10002

-- Redis 读缓存（本轮，scripts/smoke-test.ps1 全绿 + 55 项断言）
商品详情 首次回源 97ms → 命中缓存 6ms
商品列表 20 条/页 SELECT 条数 = 3（原先每条商品各查分类/点位/卖家，共 60 次）
浏览量 5 次浏览只落 Redis 计数，10 分钟后批量回写 item.view_count
清库脚本同步清理读缓存（保留 Sa-Token 会话）

-- 私聊（scripts/chat-smoke-test.ps1，72 项断言全绿；含 WebSocket 实时收发、图片、撤回、拉黑、用户主页）
无效 token 握手              → 被拒（401）
买家从商品发起会话            → 复用同一会话；卖家给自己发 → 70001
REST 发送 → 卖家 WS 实时收到  → 落库成功才回 ACK（clientMsgId 原样带回）
未读 / 已读                  → 卖家未读 2 → 标记已读后归零，买家秒收"已读"回执
"加微信转账"                 → 放行 + 弱提醒（riskLevel=2，不篡改原文）
"管制刀具"                   → 50002 拦截 + 留痕入库 + 不投递给对方
历史消息                     → id 游标翻页；被拦消息仅发送方可见
图片消息                     → POST /files/upload?biz=chat 上传 → msgType=2 实时投递（回归防线）
买卖角色                     → 买家视角 myRole=buyer/peerRole=seller，卖家视角反之
撤回                         → 内容不再下发 + 对方实时收到 recall；撤回他人消息 → 70007
拉黑                         → 双向 70006 发不出去；会话 canSend=false；解除后恢复
用户主页                     → 公开可访问（未登录也能看），响应体不含手机号字段
DB 校验                      → chat_message.content = v1:…（AES-GCM 密文）

-- 运营后台（scripts/admin-smoke-test.ps1，44 项断言全绿）
普通用户访问运营接口      → 10003 无权限访问
审核通过 / 驳回           → 商品 status 1→2 / 1→6（reject_code 落库）
重复审核 / 无原因驳回     → 60001 / 60003
批量通过                  → 一次处置多件，逐条返回失败原因
用户提点 → 运营通过        → audit_status 0 到 1，安全分可校正，随即出现在地图
封禁 / 解封               → status 3（含 ban_until）→ 1（清空）
运营封禁自己              → 60004 不能对自己执行该操作
信用分人工调整            → 100 到 105 且写入 credit_log 流水
强制下架违规商品          → status 2 到 8 违规下架
每次后台写操作            → 落 sys_audit_log（操作人/动作/前后值/IP）

-- 面交可用性修复（前端，headless Chrome 走查）
购物车默认勾选            → 首次/新加入的在售条目自动勾上（原先恒为空 → 弹窗打不开，面交走不通）
不可面交条目              → 已锁定/已售出/已下架置灰禁选 + 显示状态，点整行也能勾选
商品详情「约面交」        → 直接 POST /meetup-orders 下单并跳「我的面交」（原先只加购物车）
下拉可输入                → 分类 / 面交点 / 学校 / 校区 / 运营筛选全部 filterable；面交点底部可跳地图推荐新点位
自己约自己                → 10001 不能约自己发布的商品（接口层拦截）

-- 面交设计重做（scripts/smoke-test.ps1 全绿 + 27 项专项断言）
提交意向不锁库            → 地点/时间全留空也能提交；商品仍是在售(2)，第二个买家照样能约
卖家接受才锁库            → 商品 2→3，锁到"时间窗结束 +2h"（无时间窗锁 24h）；其他意向单自动作废
商品已被卖掉时接受        → 30004 + 该单置失效（独立事务，不被异常回滚，原先会回滚成"待确认"一直挂着）
买家「我收到了」          → 只写 buyer_confirm_at，状态不变（买家无法单方面下架卖家商品）
卖家「已卖出」            → 已售出 + 双方信用分 +2
核销码                    → 接口与前端入口全部删除（有效期陷阱：迟到/改期后这单永远完不成）
超时                      → 卖家 48 小时未确认才失效；锁过期则商品回在售并作废对应待面交单
自动提醒                  → 提交意向后给卖家发一条私聊（会话/消息实测落库，卖家未读 +1）

-- 私聊里的面交（scripts 校验 + headless Chrome 走查）
卖家在私聊窗锁定商品      → 交易卡片直接「接受并锁定商品」→ 单据待面交、商品已锁定、聊天里出现"面交已确认"提醒
被拒绝有提示              → 买家收到私聊，正文带上拒绝原因（实测消息内容含"我没法赴约：商品已经卖掉了"）
取消/成交也有提示          → 对方收到"已取消 + 原因" / "交易完成"
顶栏「我的面交」红点      → 待办计数（卖家待确认 + 买家待收货），接受后 3→2、成交后归零
私聊窗「约面交」按钮      → 就地弹出预约弹窗（原先只是跳回商品页），提交后聊天窗出现交易卡片

-- 个人资料编辑（scripts/profile-smoke-test.ps1，44 项断言全绿）
只传昵称                  → 局部更新，头像/小铺/校区原样保留
头像 + 小铺 + 校区        → 落库，且公开主页 /users/{id}/profile 同步（仍不含手机号）
传空串                    → 头像与小铺字段清空（update wrapper，非 updateById）
昵称 / 小铺名带禁售词     → 50001 拦截且不落库
外链头像 javascript:      → 10001；/static/… 与 http(s):// 放行
换学校但校区属原校        → 10001 所选校区不属于该学校
未登录 PATCH /users/me    → 10002
headless Chrome 走查      → 弹窗改昵称 → 保存 → 页面与 sys_user 同步
```

## 五、运营后台（审核工作台）

AI 机审只做"可疑转人工"，此前没有人工处置入口，这类商品会永久停在"审核中"；现在统一收敛到运营后台。

**开通运营身份**（两种方式，满足其一即可）：

1. sys_user.role_flags 置上 8 位（role_flags | 8）——推荐；
2. 环境变量 ERSHOU_ADMIN_USER_IDS=3,5 配置白名单（本地开发/应急）。

    # 把某个用户设为运营（id 可从 sys_user 表或 /users/me 获取）
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    mysql -h 127.0.0.1 -u ershou ershou -e "update sys_user set role_flags = role_flags | 8 where id = 3;"

前端顶栏下拉与"我的"页会在 isAdmin=true 时出现**运营后台**入口；接口前缀 /api/v1/admin。

| 能力 | 关键接口 |
| --- | --- |
| 概览与待办 | GET /admin/dashboard |
| 商品审核队列 | GET /admin/audit/items |
| 审核通过 / 驳回 / 批量 | POST /admin/audit/items/{id}/approve · /{id}/reject · /batch |
| 驳回原因模板 | GET /admin/audit/reject-reasons?scope=item 或 point |
| 点位审核 | GET /admin/audit/points · POST /admin/audit/points/{id}/approve 或 reject |
| **上架审核队列** | GET /admin/review/feed · /stats · POST /admin/review/items/{id}/reviewed 或 offline · /batch/{action} |
| 商品管理 / 强制下架 | GET /admin/items · POST /admin/items/{id}/offline |
| 用户管理 / 信用分 | GET /admin/users · POST /admin/users/{id}/status 或 credit |
| 面交单查询 | GET /admin/orders |
| 操作审计 | GET /admin/audit-logs |

**端到端自测**：scripts/admin-smoke-test.ps1（默认打 8080，可加 -Base http://127.0.0.1:8081/api/v1 指向其他实例；44 项断言，可重复执行）。
**私聊自测**：scripts/chat-smoke-test.ps1（同样支持 -Base，会先清空 chat 表与私聊 Redis key，可重复执行；依赖本机 node，脚本用 node 内置 fetch/WebSocket）。
**资料编辑自测**：scripts/profile-smoke-test.ps1（同样支持 -Base，用测试号 13800000003，跑完会把资料还原成默认值，可重复执行）。
**网页端后台验收**：scripts/verify-admin-console.mjs（**65 项断言**，真 Chrome；自己造数据、跑完自己清理，可重复执行）。
**上架审核页验收**：scripts/verify-admin-review.mjs（**30 项断言**，真 Chrome）。
**个人中心验收**：scripts/verify-profile-page.mjs（**20 项断言**，真 Chrome；**同时覆盖空数据与有数据两种状态**）。
**黑名单验收**：scripts/verify-blocklist.mjs（**23 项断言**，真 Chrome；含空状态、双向拉黑标记、真实解除，跑完自动清理）。
**商品编辑验收**：scripts/verify-item-edit.mjs（**20 项断言**，真 Chrome；自己发一件商品来测、跑完删掉并重建索引；含属主可见性、字段回显、改价/改标题后接口与页面双确认、已售出不可编辑）。
**合并点位验收**：scripts/verify-merged-point-e2e.mjs（**4 项断言**；自造"组内每个点位各一件商品"的现场，**用基线对比**证明"只查代表点会少显示"）。
**选点针验收**：scripts/verify-pick-pin.mjs（**11 项断言**，真 Chrome；真的开弹窗、真的点地图、读计算样式确认没画成数字气泡）。
**数据恢复**：scripts/restore-demo-data.ps1（**默认干跑**，加 `-Apply` 才改）。
演示商品默认只挂 7 天，**过一周首页会空**（这是功能正常），跑它即可重新上架 + 清测试残留 + 重建索引。

### 商品编辑（详情页 + 我的发布共用）

`components/ItemEditDialog.vue`，字段与后端 `ItemUpdateDTO` 对齐（标题/描述/分类/成色/价格/原价/可议价/库存/图片/面交点）。

- **可编辑范围以后端为准**：`ItemService.update` 只拦 `status == 4`（已售出）。
  前端别自己发明额外限制 —— 写错过一次（白名单漏了 0/8 又没排除已售出，
  导致已售出的商品还能点「修改商品」、点下去才被后端拒绝）。
- 改标题/描述/图片会**重新过内容审核**：命中禁售词直接拦（`50001`），可疑词打回「审核中」，
  商品暂时从在售列表消失 —— 弹窗里必须明说，否则卖家以为"改个名字怎么东西没了"。

### 地图合并点位：点开要看**组内全部**商品

100 米内的点位在地图上合并成一个气泡（角标是**组内点位数**，不是件数）。
点开时必须按**组内所有点位**查商品：

- 后端：`GET /items?preferPointIds=1,2,3`（逗号分隔，一次 `IN` 查询，上限 50）；
- 移动端：`PointGroup.itemQueryPointIds()`（命名方法，有单测钉着）；
- 网页端：`MapView.vue` 的 `selectedGroupIds`。

> ⚠️ 2026-10-10 修的真实 bug：移动端只按**代表点**查 → 气泡写"3 个点位"、小窗只有 1 件商品
> （用户原话"合并点只能看到一个商品"）。验收脚本同时证明了旧行为确实少显示。

### 黑名单（/blocked）
拉黑/解除的**动作**入口在聊天窗与用户主页，**查看与解除**统一在 `/blocked`
（个人中心「我的黑名单」、聊天页头部「黑名单」都能进）。

- `GET /users/blocks` 返回 `UserBlockVO`：带昵称/头像/信用/校区/拉黑时间，
  **不含手机号等联系方式**（黑名单页没有理由比公开主页看到更多）。
- ⚠️ **解除是单向的**：双向拉黑时解除只解除自己这一侧，对方仍拉黑着你，**解除后依然无法私聊**。
  列表用「对方也在拉黑你」标记 + 二次确认文案（随是否双向变化）把这件事讲明白，
  否则用户会以为"解除了还不能聊 = 解除没生效"。
- 后端做这个 VO 时注入的是 `SysUserMapper` 而不是 `UserService`，避免循环依赖；
  学校/校区名走 `DictionaryCache`，与公开主页同一口径。

> **移动端还没有黑名单页**，而且 `api_repository2.dart` 里连拉黑/解除的 API 封装都没有
> （聊天页只能读 `blockedByMe` 显示一行状态）。这是已知缺口。

### 个人中心（/profile）的信息层级

这个页面只回答两个问题，改版时请守住这个分工，别再往里堆入口：

1. **有没有要我动手的事** → 顶部待办区（等你确认的面交 / 等你确认收货 / 购物车待结算）。
   一件都没有时**要明确说"暂无待处理"**，不要留空。
2. **我最近的东西怎么样了** → 概览数字 + 「我发布的」「我收藏的」两个列表（带图/价/状态/时间），空时给引导按钮。

档案类信息（用户编号、每日配额、刷新/编辑/退出）一律放**底部一行小字** —— 要的时候找得到，不要时不占地方。

> 两个已知的口径妥协（写在这里免得被当成 bug）：
> - 「在售中 / 已卖出」是**前端按状态统计**的，因为 `/items/mine` 不支持 `status` 过滤，
>   只取了最近 100 件来数 —— 商品超过 100 件时这两个数字会偏小。
> - `creditLevel` 后端返回的是数字，页面上会映射成中文（裸数字没人看得懂）。
>
> 信用区**只显示分数 + 中文等级，不显示星级**：星级是「分数 ÷ 20 四舍五入」，
> 与分数是同一个信息说两遍，而且只在整 20 分时才准（80 分和 89 分都是 4 星）。
> 验收脚本有一条断言专门防止它被加回来。

### 管理台视觉规范（2026-10-02 整改"太花了"）

规则集中写在 `ershou-web/src/styles/admin.scss`，**一处定义、全站共用**（`main.ts` 引入）。
改样式时请遵守，别再往页面里塞颜色：

1. **颜色要有分工**：
   绿 = 品牌 / 主操作；红 = 破坏性操作（下架 / 删除 / 封禁）；橙 = 需要注意；
   **其余（状态、时间、次要信息）一律中性灰**。
   > 这是整改的主因：`--el-color-success` 与 `--el-color-primary` 是**同一个绿色**，
   > 于是"主要"和"成功"在视觉上分不开，一屏几十个绿 tag = 花。
2. **只保留一层阴影**，不要装饰性色块：统计卡左侧的彩色竖条、面板标题的绿竖条、
   侧栏底部的彩色大数字区都已去掉（那些颜色不承载信息 —— 数字本身已经说明一切）。
3. **表格是主角**：统一行高/字号；不用斑马纹；操作列一律 `link` 文字按钮；
   行内标注用中性 `.admin-review__label`，只有真危险/需注意才上色（`.is-danger` / `.is-warn`）。
4. **说明文字收进「帮助」胶囊**（`.admin-help`，默认收起）：要看看，不要时不抢注意力。
5. **同一件事只说一遍**：待办数字只在"该动手的地方"出现一次。
6. **侧栏不用品牌色**：当前项用「灰底 + 左侧深色竖条 + 深色文字」表示，默认项中性灰。
   侧栏是工具导航，一列绿底绿字会非常吵。

页面骨架：根元素加 `admin-page`，统计卡用 `.admin-stats > .admin-stat`（`is-primary` 标记"要我动手"的那个），
表格加 `admin-table`，工具条用 `.admin-toolbar`。

### 操作气泡：一律用 `showTip`，不要用裸 `ElMessage`

```ts
import { showTip } from '@/utils/feedback'
showTip('已审核：xxx', 'success')     // 一定会自己消失
showLongTip('有违规商品申请重新上架…', 'warning')  // 需要更长阅读时间的
```

> **为什么**：`ElMessage` 的默认行为是"鼠标停在气泡上就清掉倒计时"（源码
> `message2.mjs` L102 `onMouseenter: clearTimer`），移开才重新计时 —— 运营点完按钮手正停在
> 那儿，气泡就"有时候不消失"。`showTip` 用 `duration: 0` + 自己定时强制关闭，不受 hover 影响。
> 离开管理台时 `AdminShell` 会调 `closeAllTips()`，避免提示跟着用户飘到别的页面。

> ⚠️ 改 `.ps1` 脚本要留意两点：**必须带 UTF-8 BOM**（PS 5.1 否则按 GBK 读，中文全乱；
> `edit` 工具会丢掉 BOM，改完要回头确认），以及**别把 `<el-table-column` 当成 `<el-table`**
> 批量替换（前缀相同的标签要加边界 `(?![-\w])`，否则构建直接失败）。

### 测试脚本的三条硬约定（踩过很多次）

1. **必须自己造数据**，不要假设库里现成有（清过一次数据就全挂）。
2. **必须自己清理**，包括用管理员账号发的那几件（只按 `seller_id` 清会漏）。
3. **清理后必须触发一次索引重建**：清理走的是直接 SQL DELETE，ES 收不到通知，
   不重建就会攒下幽灵文档 → 搜索结果 `total` 虚高（"共 N 件"却列不出东西）。
   调 `POST /api/v1/search/reindex?sync=true`（**清空重建**，能真正清掉幽灵文档；
   `sync=true` 会等到重建完成且索引已 refresh 才返回，跑完可以立刻断言一致性）。
   > 为什么必须同步：默认是异步的（返回"已在后台开始"），这时索引还在删+重建中途；
   > 而且 ES bulk 写入默认约 1 秒后才可见 —— "接口返回了"不等于"结果已可见"。

另外两个反复踩的坑：
- **每日发布计数**（`ershou:limit:post:<uid>:<date>`）会被反复跑的脚本用光，
  只调大 `sys_user.publish_limit` 没用，必须清计数键 —— 症状是发布返回 `20003`、
  所有断言都拿到空值，看着像功能坏了。
- **前置文件**：脚本依赖 `.build/test-item.png` / `.build/test-upload.jpg`，
  缺失时上传会拿到空 URL，同样表现为"功能坏了"。

## 五·五、上架审核（实时确认"每条商品都被看过"）

运营后台第二项「上架审核」，解决的是**AI 机审覆盖不到的那一段**：机审只拦"明确禁售词"，
可疑词转人工，但一件用正常词汇写出来的违规商品（比如"管制刀具"换个说法）机审看不出来，
也没有任何机制保证它被人看过。这一页就是那个"保证"。

**产品口径（2026-10-02 定，四条）**

| # | 规则 | 实现 |
| --- | --- | --- |
| ① | 商品正常上架**不拦截**：发布成功即在售，和以前完全一样 | 入队只是"登记"，`ItemReviewService` 从不改商品状态 |
| ② | 但每条上架都要进这一页，点「已审核」后消失（归档留痕） | `item_review` 一个商品一条记录，条件更新兜并发 |
| ③ | 在售期间**改名 / 改描述**要重新回到这一页 | `item.last_live_title` / `last_live_desc_hash` 两个快照列对不上即视为内容变了 |
| ④ | **违规下架(8) 的商品二次申请上架必须审核** | `POST /items/{id}/apply-relist` → 停在「审核中(1)」→ 审核工作台通过才回到在售 |

**两条队列各管一段（别混）**

| 页面 | 管什么 | 商品状态 |
| --- | --- | --- |
| 上架审核 | **已经挂在平台上**的商品，事后逐条确认 | 在售(2) / 已锁定(3) |
| 审核工作台 | **还没上架**的：AI 可疑转人工、违规二次申请 | 审核中(1) |

所以"审核中"的商品**不会**出现在上架审核页里（那是工作台的活），
而上架审核页里的每条都 `actionable=true` —— 不会出现"列表里有、点了报错"。

**实时推送**（`ws://host/ws/admin/review?token=xxx`，握手额外校验运营身份）

| 事件 | 触发 | 前端行为 |
| --- | --- | --- |
| `queued` | 新上架 / 改名重新入队 | 拉到该条 VO 插到列表顶部并闪一下，待审数字用推送里的权威值 |
| `handled` | 某条被（可能是别人）处置 | 本地摘掉那一行，并把下一批补上来 |
| `violation-applied` | 卖家提交违规商品二次上架申请 | 弹提示引导去「审核工作台」 |

> 推的是"条数 + 单条"而不是整个列表：一个页面几十上百条，每次上架都重推全量
> 既浪费、又会让正在看详情的审核员丢失展开态。跨实例靠 Redis Pub/Sub 广播，
> 与私聊同一套机制（ADR-06），差别是**接收方是"所有审核员"**而不是某一个用户 ——
> 否则两个审核员看到的队列不一样，"保证每条都被看过"就无从谈起。

**几个踩过的坑**

- **入队失败绝不能影响发布**：登记只是副作用，发布才是用户在做的事。
  `onItemLiveOptimistic` / `onContentMaybeChanged` 全部 try-catch 吞异常，
  否则会出现最坏结果 —— 内容没发出去、审核页也没有；
- **改名要显示"从什么改成了什么"**：`item_review.title` 存的是**改之前**的旧标题
  （必须赶在快照列被覆盖前取），列表标题则一律用商品**当前**标题。
  一开始把新标题抄进快照，页面上"原名称"和标题一模一样，等于没有对比信息；
- **只改价格不该重新入队**：审核员确认的是"这条内容挂出来合不合适"，与价格无关。
  判据用 `textChanged`（标题或描述变了）而不是"有更新"；
- **一条记录而不是一条流水**：`uk_item` 唯一键 + `revision` 累加。
  审核员关心的是"这件商品**现在这一版**看过没有"，历史上被审过几次走 sys_audit_log；
- **`sellerId=abc` 曾整页 500**：`@RequestParam Long` 遇到非数字抛
  MethodArgumentTypeMismatch，被全局异常兜成 90001「系统开小差」。
  运营手输筛选框敲错一个字符是很正常的事，现在解析不了就当"没筛"，列表照常返回；
- **「下架」不等于「违规」**：审核页的下架置 **已下架(5)**（卖家改完可重新上架），
  明确违规仍走商品管理的**强制下架(8)**。下架时顺手清掉 `audit_result` 与锁库信息，
  否则商品列表里会看着像"刚审核通过"的商品；
- **列表排序与实时插入必须一致**：后端原本按"先到先审"升序，而推送把新条目插在顶部，
  结果一条商品刚插到最上面、一刷新又跳到底部。现在统一**最新在前**，
  "老条目会不会被压在下面"由列表里的**等待时长**列（超 6 小时提示、超 1 天标黄）解决；
- **统计口径必须与列表一致**：`pending` 只算"商品还在售/已锁定"的记录。
  否则顶部数字恒大于列表条数，审核员第一反应是"这页漏数据了"；
- **启动补登记**：库里在售却没有审核记录的商品（上线前的存量、或某次入队异常被吞）
  由 `ItemReviewBackfillRunner` 补登记 —— 这个页面的价值全在**完整性**上，
  漏一条不会有任何报错，只会让"每条都被看过"变成一句空话。

**存量数据怎么处理**：`sql/migration-item-review.sql` 把已经在售的商品按「已审核」入册
（review_type=5 存量导入），**不**一次性砸成待审 —— 否则上线瞬间几百条待审，
审核员分不清哪些是新来的，实时推送也就失去意义。需要重新过一遍存量时：
`DELETE FROM item_review;` 然后重启后端（启动任务会按待审重新入册）。

**回归**

- `powershell -File scripts/review-smoke-test.ps1`（74 项断言：入队/已审核/改名重入队/下架/
  违规二次申请/批量/权限/WS 实时/审计留痕/补登记/统计口径，可重复执行）
- `node scripts/verify-admin-review.mjs`（30 项断言，headless Chrome 走真实页面：
  侧栏入口与角标、实时连接、**发新商品 285ms 内自动出现**、点已审核后消失、改名标记、
  详情抽屉、下架闭环、页面无 JS 报错）
- `node scripts/probe-admin-review-ws.mjs`（WS 探针，也可单独跑，量推送延迟与鉴权）

> 两个测试脚本都会先清掉测试号的「每日验证码次数」计数 —— 后端限的是
> **每个手机号每天 10 次**，一天跑几轮就撞上 10004，表现像"验证码逻辑坏了"。
> 注意计数器 key 带日期后缀（`ershou:sms:limit:<phone>:yyyyMMdd`），
> 直接 `DEL` 不带后缀那个是删不掉的（返回 0，看着成功其实没清）。

## 六、高德地图接入（校内地图真地图模式）
校内地图是**双模式**的，由后端 `campus.map_type` 决定前端用哪个组件，两者对外 props/events 完全一致，业务代码无感切换：

| map_type | 前端组件 | 说明 |
| --- | --- | --- |
| 1 | `CampusMap.vue` | Leaflet `CRS.Simple` + 自绘底图（图片或 CSS 网格），坐标为像素 |
| 2 | `AmapCampusMap.vue` | 高德地图 JS API 2.0，真实经纬度、定位蓝点、米制距离、选点即得 lng/lat |

**1) 配置 Key**（已写入仓库根目录 `.env`，该文件不进 Git）：

```ini
# 给【前端 Vite】读：必须带 VITE_ 前缀才会注入浏览器
VITE_AMAP_JS_KEY=            # 控制台 → 应用管理 → 添加 Key → 服务平台必须选「Web端(JS API)」
VITE_AMAP_JS_SECURITY_CODE=  # 与 key 同页面显示的「安全密钥」，2021-12-02 后创建的 key 强制要求

# 给【后端 Spring】读：移动端地图是后端下发 HTML，key 从服务端配置取
AMAP_JS_KEY=                 # 与上面同一个 key，换个变量名
AMAP_JS_SECURITY_CODE=
```

> ⚠️ **三个必须记住的坑**（本项目都踩过）：
> 1. **前端那两个变量名必须带 `VITE_` 前缀**。写成 `AMAP_JS_KEY` 时 vite 不会注入浏览器，
>    `import.meta.env.VITE_AMAP_JS_KEY` 是 `undefined`，前端会静默退回自绘平面图，**不报错**。
>    自检：`curl http://localhost:5173/@vite/client` 看不到你的变量 = 前缀写错了。
> 2. **同一个 key 要配两套变量名，只配一套会"坏一半"**（2026-10-02 踩过）：
>    只配 `VITE_` 那两个 → **网页地图正常、移动端地图打不开**，因为移动端走
>    `GET /api/v1/map/amap-html`（后端渲染下发 HTML 给 WebView），读的是 `AMAP_JS_KEY`。
>    后端现在也认 `VITE_` 前缀那两个作为兜底，但正式变量名建议都配上。
>    自检：`curl http://127.0.0.1:8080/api/v1/map/amap-status` 应返回 `configured: true`。
> 3. **不要用 REST 接口验证 JS API Key**。`restapi.amap.com/v3/security/check` 是给「Web服务」Key
>    做数字签名用的，拿 JS API Key 去打只会返回 `USERKEY_PLAT_NOMATCH (10009)` ——
>    那是"这把 key 不属于我这个平台"的意思，属于**正常反证**，不代表 key 有问题。
>    JS API Key 唯一可靠的验证方式是在浏览器里真的渲染一张地图出来。
> 4. 前端通过 vite 的 `envDir` 读根目录 `.env`（见 `ershou-web/vite.config.ts`），改完 `.env` **必须重启 dev server**；
>    后端同理，改完要**重启后端**（`application-local.yml` 优先级高于环境变量，见第 10.5 节）。
> 5. **`.env` 由后端自己在启动时自动加载**（2026-10-02 修）：
>    `DotenvEnvironmentPostProcessor` 会在 Environment 就绪后、任何配置绑定之前读项目根目录的 `.env`，
>    所以**不管你怎么启动后端（脚本 / IDEA 点 Run / 打 jar）都能拿到 key**，不需要每次手动配。
>    启动日志里会有 `[dotenv] 已从 …\.env 载入 N 个变量` 这一行作为证据。
>    > 为什么之前会"每次重启都要配"：`.env` 原本只有 `scripts/dev-server.ps1` 会读，
>    > 从 **IDEA 直接 Run** 时环境变量是空的 → `ershou.amap.js-key` 解析成空串 →
>    > 移动端只显示"后端未配置高德 key"。key 一直没丢，是**读取路径依赖了启动方式**。
>    >
>    > 仍然为空的唯一可能：`.env` 里确实没配，或后端工作目录不是 `ershou-server`（那样找不到 `../.env`）。
>    > 另注：`.env` 被设为**最高优先级**（`addFirst`），所以没法再用环境变量临时覆盖单项 —— 请直接改 `.env`。

**1.5) 移动端地图自检**（真实浏览器加载后端下发的 HTML，看瓦片有没有画出来）：

```powershell
# 前置：后端已启动（不需要前端 dev server）
node scripts/verify-amap-mobile-page.mjs
# 16 项断言：key 已配 → HTML 注入正确 → 高德引擎真的渲染（版权行/比例尺/画布非空）→ 点位气泡
# 截图：.build/amap-mobile/01-mobile-map.png
```

> 判据为什么不是"瓦片 img 数量"：高德 JS API **2.0 把瓦片画进 canvas**，
> DOM 里没有瓦片 `<img>` 节点，数 img 永远是 0（误判成"地图没渲染"）。
> 可靠判据是页面里出现 `© … AutoNavi` 版权行 + 比例尺 + canvas 上有非空像素。

**1.6) 地图页「点位商品小窗」的布局约定**（别再让固定高度算漏）：

```powershell
flutter test test/map_point_items_window_test.dart   # 7 项，纯 widget 测试，不需要真机
```

**1.7) 地图选点针：两端必须是同一套画法**

```powershell
node scripts/verify-pick-pin.mjs   # 11 项，真 Chrome：真的开弹窗、真的点地图、读计算样式
```

移动端选点页与网页端选点弹窗**用的是同一套后端下发的 HTML**（`GET /api/v1/map/amap-html`），
所以选点标记也该完全一致 —— 都是 `.pickmark` 那枚水滴针：
橙色圆头 + 白描边 + 白芯（`::before` 8px）+ 旋转 45° 的尖（`::after` 11px）+ 地面小椭圆 + 投影 + 掉落动画。

> ⚠️ 2026-10-02 踩过：网页端不管什么标记都画成"数字气泡"（`itemCount ?? 0`），
> 而**正在选取的新点位 itemCount 恒为 0** —— 于是选点标记上是个难看的「0」（用户原话"零图标"）。
> 现在网页端用 `pickPinId` 把这一类标记单独渲染成针。
>
> **锚点必须是 `bottom-center`**：针的尖要落在点下去的位置。用默认的 `center` 会让针尖
> 悬在点击点上方半个针高（18px），"点哪儿打哪儿"就偏了。移动端也是 `bottom-center`。

`ui/widgets/map_point_items_window.dart` 里的高度是**互相引用的常量**，改任何一个都不会算漏：

```
卡高 118 = 图 76 + 间距 5 + 标题 14 + 间距 2 + 价格 21
窗口 134 = 卡高 118 + ListView 上下内边距 8×2
```

> ⚠️ 2026-10-02 踩过：小窗高度硬编码 132 而内容是 134 —— 真机上 Flutter 直接把
> `BOTTOM OVERFLOWED BY 3.0 PIXELS` 的黄黑条画在小窗上，价格那行被挤掉，
> 用户看到的就是"小窗显示有问题"。**固定高度的容器，每个孩子的高度都要显式定死**
> （标题 14 / 价格 21），别让它们按字体自己撑 —— 系统字号一变就溢出。
>
> 写这个测试本身也有两个坑（已写在测试文件顶部）：
> `NetImage` 是 `ConsumerWidget`，不 override provider 会抛异常并拿到**无限高度约束**
> （报一个 10 万像素的假溢出）；`PriceText` 内部是 `RichText`，
> `find.textContaining` 抓不到，要按 `RichText` 的合并文本找。


**2) 自检 Key 是否可用**（用真实浏览器渲染，截图作为判据）：

```powershell
# 前置：ershou-web 目录下 npm run dev 已启动
node scripts/amap-key-check.mjs
# 跑完看 .build 目录下最新生成的 amap-key-check.png：出现真实街道瓦片 = Key 可用
```

**3) 切换校区到真地图 + 配中心点**：

```powershell
mysql --host=127.0.0.1 --port=3306 --user=ershou --password=*** --database=ershou < sql/migration-amap.sql
```

> ⚠️ 改完数据库**必须清 Redis 字典缓存**，否则接口仍返回旧的 `mapType`（校区字典 TTL 1 小时）：
> ```powershell
> docker exec ershou-redis redis-cli -a <密码> --no-auth-warning DEL ershou:dict:campus:1 ershou:dict:school:1
> ```
> 现象就是：SQL 明明改了 `map_type=2`，`GET /api/v1/map/points` 却还返回 `mapType: 1`。

**3.5) 桂电三个校区（`sql/migration-guet.sql` + `sql/migration-huajiang.sql` 已执行）**

坐标来自高德 POI 检索（GCJ-02，可直接入库）：

| campus.id | 校区 | 中心坐标 | zoom | campusSizeMeters |
| --- | --- | --- | --- | --- |
| 1 | 金鸡岭校区（金鸡路1号） | `110.336881, 25.283964` | 16 | 800 |
| **2** | **花江校区（灵田镇，主校区，当前默认）** | `110.416819, 25.311724` | 15 | 1500 |
| 3 | 六合路校区（六合路123号，原西校区） | `110.326284, 25.280997` | 16 | 700 |

当前默认校区由 `ershou-web/src/utils/constants.ts` 的 `DEFAULT_CAMPUS_ID = 2` 指定；
`item` 与 `trade_point` 的 `campus_id` 也都已迁到 2。**换校区**：改这个常量 + 迁移该表数据
+ 清字典缓存（`ershou:dict:campus:<id>`）三步。

**花江校区的面交点已换成真实地点**（不再是"图书馆东门广场"这类种子数据）：

| id | 名称 | 真实坐标 (GCJ-02) | 来源 |
| --- | --- | --- | --- |
| 1 | 花江校区图书馆 | `110.418835, 25.313315` | 高德 POI |
| 2 | 中央食堂正门 | `110.417475, 25.312275` | 高德 POI |
| 3 | 菜鸟驿站(中央食堂旁) | `110.416495, 25.316311` | 高德 POI |
| 4 | 花江校区南门 | `110.419324, 25.304553` | 高德 POI |
| 5 | 南二门门卫室旁 | `110.416719, 25.304731` | 高德 POI |
| 6-8 | 中央食堂快递站 / 雅园餐厅门口 / 清苑宿舍区 | 见 `sql/migration-huajiang.sql` | 高德 POI |

**校验地图到底定位到哪了**（无头环境瓦片可能不渲染，但中心点可读）：

```powershell
# 地图组件会把当前视野中心写到容器上，读它就知道定位对不对
chrome --headless=new --dump-dom http://localhost:5173/map | Select-String 'data-map-center'
# 花江校区期望：110.417…,25.311…（略偏是 fitView 把点位全框进来的结果）
```

**3.6) 发布商品选真实地址（地图选点）**

发布页「常用面交点」下拉里有 **`地图上新增地点`** 按钮 → 弹出高德地图 → 点任意位置，
自动逆地理编码识别地点名（如"花江校区图书馆"）→ 填写名称/类型/指引 → 「添加并使用」。

设计取舍（为什么不做自由文本地址）：

- 自由文本地址无法参与地图聚合，会毁掉"地图找货"这个核心功能（需求文档 FR-MAP-03/05）；
- 所以真实地址走**点位库**：选中即创建 `trade_point`；
- 同名点位后端直接复用（`TradePointService.createByUser`），防止同一地点被反复提交刷屏。

**免审核（2026-09-30 起）**：用户自定义点位**不需要审核**，`audit_status` 直接置 1，
创建后立刻出现在校区点位列表和地图上，**买卖双方都能看到**。

**不做任何同名合并（同日修正）**：早期实现里"同名点位直接复用"，结果是用户在发布页
选了个新地址（或只是名字撞了），却被悄悄合并到旧点位上，看起来像"没生效/被吞了"。
现在 `createByUser` 与 `suggest` **每次调用都新建一条独立记录**：

- 同名的两条点位各自有 id、各自有坐标，地图上就是两个标记；
- 前端在检测到重名时会提示「已新增《X》（与其他点位同名，但两条独立存在）」，
  而不是假装成功；
- 重复点位交给运营按 id 删除（不做隐式合并——隐式合并比多几条脏数据更难排查）。

回归：`scripts/verify-no-merge.ps1`（7 项断言，含"suggest 接口同样不合并"）。

> ⚠️ 踩过的坑：`listByCampus` 原来带 `audit_status=1` 过滤，而建点时写 0（待审），
> 结果新点位谁都看不见；改成免审核后又漏了 `evictor.pointChanged(id)`，
> 被"地图点位列表 30 分钟版本号缓存"挡住，依然看不见（测试里 4 项断言红了才发现）。
> **凡是写 `trade_point` 的地方，写完必须调 `CacheEvictor.pointChanged()`。**

**没有商品的点位会被清掉（2026-09-30 追加）**

规则（`TradePointService.cleanupEmptyPoints()`，定时任务每天 03:10 + 运营可手动触发
`POST /api/v1/admin/trade-points/cleanup-empty`）：

点位上**没有任何"还有戏"的商品**，且点位**创建超过 7 天**，才删除。

- "还有戏"= `status IN (0,1,2,3)`：草稿 / 审核中 / 在售 / 已锁定。
  已售出(4)、已下架(5)、审核驳回(6)、已过期(7)、违规下架(8) 都是终结态。
- **为什么不能在售(2)一条标准**：商品在"审核中"就把点位删了，审核通过后面交点就没了。
- **为什么要 7 天宽限期**：用户刚在地图上新增的点位，可能过一会儿才发布商品挂上去，
  建完立刻删会让人以为"新增失败"。
- 删完必须 `evictor.bumpMapVersion()`，否则前端还能看到幽灵点位。

回归：`scripts/verify-cleanup-empty.ps1`（6 项断言，覆盖"老点位无商品/只有终结态商品/
有审核中商品/新点位/有在售商品"五种情形）。

> ⚠️ 同一轮把气泡数字的口径也改了：`itemCount` 从"在售(2)"改成"还有戏(0/1/2/3)"，
> 否则"审核中的商品"在业务上属于这个点位、气泡却显示 0，用户会以为点位是空的。

**3.7) 私聊页改面交地址**

私聊窗顶部的交易卡片上，买卖双方都能点 **`改面交地点` / `约定面交地点`**（状态为 1 待确认 / 2 待面交时）：
地图选点（可新建地点）→ 立刻写回面交单并**给对方发一条私聊提醒**。

| 接口 | 说明 |
| --- | --- |
| `POST /api/v1/trade-points` | 新增点位，支持 `lng/lat`（GCJ-02）或 `coordX/coordY`（像素），返回点位 VO |
| `GET /api/v1/trade-points/my-points?campusId=` | 我提交过的点位（发布页刷新后仍能选中自己新增的地点） |
| `PATCH /api/v1/meetup-orders/{id}/point` | 改面交地点（可选同时改时间窗），买卖双方都可调 |

约束与行为：

- 只有**待卖家确认(1) / 待面交(2)** 能改；已完成/已取消/已失效会拒绝（`30003`）；
- 只能选**本校区**的点位（避免挂上外校的点，地图上找不到）；
- 改完给对面发私聊：`【面交地点已更新】《商品名》买家/卖家把面交点改成了：XXX`；
- `reschedule_count` 每次 +1，可用于后续风控（反复改地址的人）。

> ⚠️ 这三个接口需要**重启后端**才生效。验证：`scripts\amap-point-test.ps1`（37 项断言全绿）。

**3.8) 地图页"选点模式"的两个坑（已修）**

现象：在地图页开了「选点模式」点一下新增点位，**新选的点不出现**，地图上还是老标记。

两个原因叠在一起：

1. **坐标系传错**：`AmapCampusMap` 在高德模式下 `@pick` 回调里的 `coordX/coordY`
   **装的是经纬度**（沿用旧字段名），而地图页提交时一律发 `coordX/coordY`，
   后端也就把经纬度当像素存进了 `coord_x/coord_y` —— 点位坐标虽然存下了，但
   在地图上按像素规则解释，位置完全不对（真脏数据可查 `coord_x` 是否约等于 110）。
   现在按地图类型分流：高德 → 发 `lng/lat`；自绘平面图 → 发 `coordX/coordY`。
2. **列表不刷新**：`POST /trade-points/suggest` 原本返回 `void`，前端拿不到新点位，
   只能干等缓存过期；而 `suggest` 之前**没有** `evictor.pointChanged()`，缓存根本不失效。
   现在该接口返回点位本身，并复用 `insertSuggest()`（内部已清缓存）。

顺带修掉一个 UX 硬伤：**别的页面新增点位后地图页不刷新**。现在 `useTradePoints` 暴露
`pointsVersion`，任何新增点位都会 +1，地图页 `watch` 它自动重拉点位与商品。

回归断言在 `scripts/amap-point-test.ps1` 第 9 节（6 项）：

```
[PASS] 选点接口返回点位本身（不再是 void）
[PASS] 经纬度写进了 lng/lat
[PASS] 没有把经纬度误写成像素 coordX/coordY
[PASS] 新选的点立刻出现在「全部面交点」列表
[PASS] 新选的点立刻出现在地图数据里
```

**3.9) 地图页改版（2026-09-30）**

**职责边界（按产品要求调整过）**：

- 地图页**只负责"看"**：看校区所有面交点、看某件商品的位置、导航过去。
- **新增面交点只在发布/交易流程里做**：发布页「地图上新增地点」、私聊页改面交地址。
  地图页上的"＋ 新增面交点 / 选点模式"已**移除**（曾经加过，产品明确不要）。
  `AmapCampusMap` 仍保留 `v-model:pickable` + `@pick` 能力，供发布页与私聊页复用。

**三个明确的行为**：

| 入口 | 地图显示 |
| --- | --- |
| 导航菜单「校内地图」 | **全部面交点**（受搜索 / 只看有货影响） |
| 商品详情「在地图上看」（带 `?itemId=`） | **只显示这件商品所在的那一个面交点**，红色气泡 + 呼吸圈；顶部提示条可一键「显示全部点位」 |
| 商品没设面交点时 | 不进入焦点模式，正常显示全部（避免地图空着让人以为坏了） |

**点交易点 → 就地看商品（移动端，2026-10-02）**

点地图上的交易点，底部弹**商品小窗**（高 **134px**，横向滑动，只占屏幕约 1/6）：

- 小窗在上、点位信息条在下，**不跳页**（以前点「看商品」会 push 一个整页列表，地图就看不见了）；
- 小窗里的卡片是"图 + 标题一行 + 价格"，整块可点进商品详情；
- 点位信息条右侧保留了「看全部」入口（要挑选时仍可进整页）；
- 一件都没有时小窗**不占位置**（信息条里已经写了"暂无在售"）。
- 点的是**合并气泡**（角标是组内点位数）时，小窗列的是**组内所有点位**的商品，不是只有代表点那一件。

**左上角「我附近」窗口（移动端，2026-10-10 改成可折叠）**

- 列表条数取决于周围有多少点位（实测能到 **12 条**），所以**高度封顶 210px**，超出内部滚动；
- 点标题栏折叠成一个小胶囊（显示"我附近 N"），**折叠状态写本地**，重进地图保持收起；
- 抽成独立组件 `ui/widgets/map_nearby_window.dart`，有 7 项 widget 测试钉着
  （写测试时当场抓到收起态胶囊写死宽度导致 `RenderFlex overflowed by 19 pixels`）。

> ⚠️ **两个件数口径别混用**（踩过）：
> `itemCount` 是"还有戏"（草稿/审核中/在售/已锁定），`onSaleCount` 才只有在售。
> 气泡当初用的是前者，于是出现"气泡写着 11 件在售、点开小窗只有 10 件" ——
> 差的那些还在审核中，买家本来就看不到。现在**展示给买家的都用 `onSaleCount`**，
> 但**清理空点位等运营逻辑仍必须用 `itemCount`**（审核中的商品也算这个点位的，见上文）。

**按点位查商品**：`GET /api/v1/items?preferPointId=2`（单个点位）
/ `GET /api/v1/items?preferPointIds=1,2,3`（**合并组**，逗号分隔、上限 50 个）
（以前移动端是"拉 50 条再按 `preferPointName` 过滤"：同名点位会串、超过一页就漏。
注意 ES 索引里没有这个字段，所以带 `preferPointId` 的查询**强制走 MySQL**，
见 `ItemSearchService.supports`。）

> ⚠️ **改了接口响应结构要清地图缓存**：`/map/points` 有 30 分钟 Redis 缓存
> （`ershou:map:points:{campus}:v{ver}`），版本号只在写点位时自增 ——
> 部署新代码后缓存里还是**旧结构**（新字段是 null），表现是"新写的字段没生效"。
> 排查前先 `DEL ershou:map:points:*`。
> 同理：`Item` 实体上 `select(...)` 精简列时，**上层要用到的列必须一起 select**，
> 否则字段是 null、过滤静默失效（`onSaleCount` 恒为 0 就是这么来的）。

修的问题：

| 问题 | 原因 | 现在 |
| --- | --- | --- |
| **商品显示不全** | `pageSize: 100` 写死 | 抽成 `ITEM_PAGE_SIZE = 60` |
| **选中点位会顿一下** | `watch(selectedId)` 清空并重建全部标记 | 标记按 id 索引，只改内容与层级 |
| **"附近"范围不合理** | 硬编码 300 米 | 跟着 `campusSizeMeters` 走 |
| **地图固定 640px** | 小屏被压、大屏浪费 | 自适应窗口高度，侧栏等高 |
| **"复制校区配置"是开发者功能** | 摆在主操作区 | 移除 |
| **只能看不能导航** | 无跳转入口 | 点位详情 / 商品卡片加 **`怎么去（高德导航）`**，URI 跳转**不消耗配额** |
| **单点视野顶到最大级别** | `setFitView([单点])` 会放到 z≈18 | 单点时用配置的 zoom 居中 |

新增：点位搜索、只看有货、看全部点位（地图浮动按钮，`show-fit-button` 控制）、
商品定位卡片（看商品详情 / 怎么去）、列表红框标出商品所在点位。

**验证口径**（踩过两次坑，记下来）：

- ❌ `--dump-dom` 对这个 SPA **读不到渲染结果**（恒为空壳 151336 B），别拿它当判据；
- ⚠️ 无头 `--screenshot` 偶发不产出文件（Chrome 端问题，与代码无关），
  验证前先 `Stop-Process chrome`，并用简单页面确认截图能力还在；
- ✅ 可靠判据：`npm run build`、模块 HTTP 200 且不含 `Transform failed`、
  后端接口断言（`scripts/amap-point-test.ps1`）。

`sql/migration-amap.sql` 会把 `campus.map_type` 置 2，并写入 `map_config`：

```json
{
  "center": { "lng": 116.397428, "lat": 39.909230 },
  "zoom": 16,
  "pixelBounds": { "width": 800, "height": 600 },
  "campusSizeMeters": 800
}
```

**⚠️ `center` 必须改成你校区的真实 GCJ-02 坐标**，否则高德会显示到默认城市去。
地图页右上角的**「复制校区配置」**按钮会把当前视野中心导出成同一段 JSON，直接粘回 `map_config` 即可。

**4) 关于坐标系（最容易翻车的地方）**

- 高德用 **GCJ-02**；浏览器定位 / GPS 原始数据是 **WGS-84**，国内相差 50~200 米。
- 点位显示优先级：`trade_point.lng/lat`（真实 GCJ-02）→ 否则按 `map_config.center` 把像素坐标等比换算。
  所以老数据**不采集也能先看到点**，位置是近似的；用[坐标拾取器](https://developer.amap.com/tools/picker)采到真实值后写入 `lng/lat` 即自动变精确。
- 定位坐标只在前端内存中使用（`myLocation`），**不上传后端**，符合需求文档 FR-MAP-14「不上传移动轨迹」。

**5) 免费额度提醒**（[官方定价](https://lbs.amap.com/upgrade#price)，个人认证开发者）

| 服务 | 月配额 | 本项目用量 |
| --- | --- | --- |
| 基础地图定位（JS 图面初始化） | 150 万次 | 每次打开地图页 1 次，充裕 |
| 基础 LBS（逆地理/路径规划） | 15 万次 | 选点时的逆地理编码，只在一次用户动作里调 |
| 基础搜索（POI/输入提示） | **5000 次** | ⚠️ 极紧，**靠"先数据库、后高德"压住**（见下） |

**移动端（Flutter `ershou_app`）怎么接的高德**

| 项 | 做法 |
| --- | --- |
| 真地图 | **WebView 承载高德 JS API**，页面由后端下发：`GET /api/v1/map/amap-html?campusId=&pickable=&focusPointId=&wantRegeo=&mapPoints=` |
| key | **不打进 App、用户也不用手输**：后端把 `ershou.amap.js-key` 注入页面再下发，换 key 不用发版 |
| 事件回传 | 页面通过 `FlutterBridge` 回传 `ready / select / pick`；Flutter 用 `runJavaScript` 调 `__ershouCenter / __ershouLocate / __ershouFocus` |
| 逆地理编码 | **在页面里用 `AMap.Geocoder` 做**（`wantRegeo=true` 时把候选一起回传）。原因：现有 JS API key 打 `restapi.amap.com` 会被 `USERKEY_PLAT_NOMATCH` 拒绝，用它就不必再申请 Web 服务 key。服务端另留 `/map/regeo` 兜底（读 `ershou.amap.web-key`，没配就返回空） |
| 定位 | 原生 `geolocator` 拿 WGS-84 → `core/utils/coord.dart` 转 GCJ-02（转换 0 配额） |
| 附近点位 | 服务端 Redis GEO，0 高德配额 |
| 100 米合并 | `core/utils/point_merge.dart`，与 Web 端同规则；**只影响展示**，`members` 保留每条精确点位与 id |

> ⚠️ 自定义 marker 的 content 必须自己写 `pointer-events:auto`：AMap 给 marker 外层设了
> `pointer-events:none`，不加的话**气泡画得出来但完全点不动**（真机排查了很久才定位）。

**发布页 / 私聊页的面交点选择**

- 发布页「地图上选」→ `PointPickerPage(pickExisting: false)`：点地图 → 逆地理编码识别地名 → `POST /trade-points` 建新点 → 回填选中；
  - 点地图会**立刻打一个橙色水滴针**（不等逆地理编码回来），针尖对准坐标；拍板某个候选地点时针会挪到高德认定的 POI 位置；
- 排查"点了没反应"的利器：页面把 `ready` 事件带 `count / markerCount / merged` 回传，
  Flutter debug 下打印每个桥接事件（`debugPrint('[map] ...')`）——一眼能看出"画了几个""事件有没有到"。
- **面交地点与时间只有卖家能改**（商品在卖家手上，地点时间由他定；买家想改在私聊里商量）：
  卖家入口在「我的面交 → 我卖的」订单卡的「改面交地点」（选点位 + 选时间窗）；
  后端两道校验 —— 订单侧 `updatePoint` 校验 `sellerId`，商品侧 `PUT /items/{id}` 校验 `ITEM_NOT_OWNER`；
- 私聊页交易卡片「改地点」→ `PointPickerPage(pickExisting: true)`：**点气泡选现成的**（右上角变「就用这个」），点空白处则新建 → `PATCH /meetup-orders/{id}/point`；
  **只有卖家能改**面交地点与时间（商品在他手上，地点时间由他定；买家想改在私聊里商量）——后端 `updatePoint` 强校验 `sellerId`，前端只是把入口藏掉；
- 改完对方收到私聊提醒「【面交地点已更新】…」，`reschedule_count` +1。

回归：
- `node scripts/verify-point-merge-mobile.mjs`（16 项：合并不吞 id/坐标、100 米阈值语义、不链式传递、两端规则一致）
- `powershell -File scripts/verify-change-meetup-point.ps1`（20 项：买卖双方可改、异校区拒绝、状态限制、提醒送达、未登录 10002、第三方不可改）

> 两个测试都踩过的坑：**私聊内容是加密存储的**（库里是 `v1:xxx` 密文），验证通知必须走接口读明文，
> 直接 SQL `LIKE '%文字%'` 永远查不到。

**约面交的规则（买家只提意向，卖家定地点时间）**

- 买家点「约面交」**只表达意向**：不填面交点、不填时间窗；
- 服务端**忽略**买家传的 `pointId` / `timeSlot*`：面交点改用**商品自己关联的点位**（卖家发布时选的），时间窗留空 = 待协商。这是防"直接调接口替卖家做决定"，前端不传只是第一层；
- **同一买家 + 同一商品同时只能有一张进行中的面交单**（状态 1/2）：重复提交返回 40003；购物车合并结算走同一道校验，绕不过去；
- 前端：详情页按钮约过就灰并改文案（「已约，等卖家确认」/「已约好，待面交」），下单成功后立即刷新；
- 回归：`powershell -File scripts/verify-book-once.ps1`（17 项）+ `node scripts/verify-book-button-mobile.mjs`（13 项）

**购物车（`/api/v1/cart`）**

- **加购即校验**：已售出 / 已锁定 / 自己的商品 / 超库存都会被拒（和结算同一套口径），不然前端显示成可结算、到结算才失败，用户白跑一趟；
- **数量夹取**：孤品恒为 1，其余夹在 `[1, 库存]`；
- **结算不会因一件坏商品整批失败**：先按状态分流，能约的照常提交，不能约的逐条回报在 `skipped: [{cartId,itemId,title,reason}]` 里，前端弹窗列出哪件、为什么；
- 拆单语义：**每件商品一张面交单**（同一个卖家的多件也是多张），只是共用一个 `batchNo`；
- 移动端与 Web 端同一套接口与语义；回归：`powershell -File scripts/verify-cart.ps1`（31 项）

**私聊的两个历史坑（都修了，别再踩）**

- **渲染路径上的取值一律要兜底**：`canRecall` 里 `msg.createdAt.replace(...)` 一旦命中缺字段的消息，就会在**模板渲染时抛错** —— Vue 的渲染是一抛全停，整棵子树停止更新。表现极具误导性：**按 Enter 发不出去、输入框不清空、发送按钮永久 loading**，看着像"发送功能坏了"，其实是渲染崩了把 submit 的后续语句一起带走了。现在所有消息入口（历史 / WS `message` / WS `ack` / 撤回）都过 `normalizeMessage()` 补字段；
- **新号每日私聊限额默认关闭**（`ershou.chat.daily-limit-enabled=false`）：校园场景"注册 7 天内每天 20 条"太容易误伤，正常砍价来回几句就见底；代码与配置都保留，需要时打开开关即可。

**私聊实时性：链路怎么验、断线怎么发现**

- 链路三段分开验，别一上来就怀疑后端：
  `node scripts/probe-ws.mjs <token>`（能不能连；Vite 代理 5173 与后端 8080 各测一遍）→
  `node scripts/probe-ws-realtime.mjs <aToken> <bToken> <sessionId> <peerId>`（A 发 B 收，验投递）→
  `node scripts/probe-web-realtime.mjs <bToken> <aToken> <sessionId>`（真浏览器里看 DOM 有没有自动更新）；
- **WS 帧是扁平的**：`{type:'send', sessionId, msgType, content, clientMsgId}`。
  套一层 `data` 会让服务端把 sessionId 读成 0 → `70002 会话不存在`（写探针时踩过这个坑）；
- 推送**绝不静默丢弃**：列表还没加载时先补历史再合并（以前 `if (!list) return` 直接扔，
  表现就是"对方发了消息我要刷新才看得到"）；
- 心跳带**看门狗**：超过 2 个周期没收到 `pong` 判定假死 → 主动断开重连。TCP 层"连着"但僵死时
  `onclose` 不会触发，没有看门狗界面会静默地不再实时；
- 断线时聊天窗顶部有明确提示条（`node scripts/probe-ws-blocked-notice.mjs <token> <sessionId>` 验证）。
- **重连要能自愈**：`connect()` 里 socket 不在 OPEN 态就丢弃重建，并且**每次重连都重置退避计数** —— 否则「连上→断→重试耗尽」之后就永久不再重连，只能刷新页面；
- 排查聊天页前端问题先跑 `node scripts/probe-chat-errors.mjs <token> <peerToken> <sessionId>`：它在「收到推送」前后各测一次输入框是否还活着，并把 pageerror / Vue warn 全部打出来（渲染报错会让整棵子树停止更新，表现正是「打字没反应 + 消息不出现」）。

**红点规则（网页端导航）**

- 「购物车」件数 / 「我的面交」待办：**在对应页面自己不显示**（人已经站在那一页了，气泡就没意义）；
- 「消息」未读红点**不受此规则影响**：一个会话已读不代表别的会话没新消息；
- 「我的面交」页内两个 tab 也各带待办数字（`asBuyer` / `asSeller`），与导航红点同一份数据；
- 回归：`node scripts/verify-web-badges.mjs`（6 项，需 `WEB_TOKEN` 与在跑的 dev server）

**未读角标（`GET /api/v1/chat/unread`）以 DB 为权威**

Redis Hash `ershou:chat:unread:{uid}` 只当快速读取路径，**不做权威**：

- 读取时会拿 DB 的会话集合校验缓存，**剔除已删除会话的僵尸字段**并顺手清掉；
- 为什么必须这样：会话删掉后 Hash 字段没人清，未读会**只增不减** —— 实测出现"底部角标一直显示 11、库里未读其实是 0"（Redis 里 9 个字段、8 个是已删会话留下的），用户只能重装才能消掉；
- 回归：`powershell -File scripts/verify-unread-badge.ps1`（14 项，含"删会话后未读立刻归零"）。

**附近点位用 Redis GEO（`GET /api/v1/trade-points/nearby`）**

"我附近有哪些面交点"是最自然的需求之一，所以**刻意不让它依赖高德周边搜索**（5000 次/月）：

| 环节 | 实现 | 高德配额 |
| --- | --- | --- |
| 拿我的位置 | 浏览器 `navigator.geolocation`（原始 WGS-84） | **0** |
| 坐标转换 | `AMap.convertFrom`（权威，失败退回本地公式） | **0**（JS API 本地能力） |
| 半径检索 | **Redis GEO** `GEOSEARCH BYRADIUS ... WITHDIST ASC` | **0** |

- 索引 key：`ershou:map:geo:{campusId}:v{version}`，member = `pointId`；
- **失效方式与地图缓存一致**：写点位时 `CacheEvictor.pointChanged()` 自增版本号，旧索引立刻不可达，下次查询按 DB 重建（不需要精细维护增删）；
- 只索引**有经纬度**的点位（自绘底图校区的像素坐标不是地理坐标）；
- 重建加 30 秒短锁，避免热点时刻多个请求一起读全表；索引 TTL 6 小时；
- 可见性规则与地图一致：`source=3`（搜过但没挂商品）的点位不出现在附近列表里。

⚠️ **坐标系坑（务必看）**：浏览器定位给的是 WGS-84，入库的是 GCJ-02。**桂林这一带的偏移实测有 475~567 米**（不是常说的"50~200 米"），不转换"附近"会完全选错。生产路径优先用高德的 `AMap.convertFrom`，本地公式（`utils/coord.ts`）只作兜底——两者实测恒定差约 200 米，高德的数据才是权威。回归：`node --experimental-strip-types scripts/verify-gcj02.mjs`（22 项断言）。

回归：`powershell -File scripts/verify-geo-nearby.ps1`（10 项断言：索引重建、按距离排序、半径生效、空结果不报错、成员数与 DB 一致）。

**省配额：先数据库 → 再本地缓存 → 最后才高德**

用户点「在高德上核对这个名字」时，按这个顺序找，**只有第 4 步才消耗配额**：

| 顺序 | 来源 | 配额 | 说明 |
| --- | --- | --- | --- |
| 1 | **点位库**（`trade_point`） | 0 | 别的同学建过、自己建过、**或之前搜过已落库**的，直接命中 |
| 2 | **本地缓存**（localStorage） | 0 | 30 天有效、按校区隔离、最多 200 条 LRU；命中时**补写进数据库**，让下一轮重新回到"数据库优先" |
| 3 | 本次选点已识别的候选 | 0 | `AMap.Geocoder` 返回的那一批里过滤同名的 |
| 4 | 高德「地点搜索」`AMap.PlaceSearch` | **1 次** | 只在用户主动点击时调；结果写本地缓存，**用户点选后立刻落库** |

**"搜到的地点落库"是怎么做的**（`POST /api/v1/trade-points/search-hit`）

- 落库时 `trade_point.source = 3`（`1=官方 2=用户提交 3=高德搜索落库`），前端/运营能区分"搜出来的地名"；
- **同名幂等**：先按"校区 + 名称"查库，已有就复用那条（并把当前用户记成创建者，保证他下次在"我的点位"里能选到），**不会重复插、也不会再调高德**；
- **地图只画有商品的点位**（2026-10-01 起）：`/map/points` 直接把 0 商品的点位滤掉——地图是用来找货的，一堆 0 件气泡纯属干扰。**但 `/trade-points`（发布页/私聊选点）必须保留空的**，否则用户刚建的点位在发布时选不到。两种可见性结果不同，所以后端用了**两个缓存 key**（`ershou:map:points:{campus}:v{ver}` 与 `…:items:v{ver}`），共用一份缓存必然有一边是错的；
- **搜索落库的点位（source=3）**：无论哪个开关，都要挂了商品才出现，否则搜过的地名会污染列表；
- **会被回收**：空点位清理任务（每天 03:10）会把"宽限期内没有任何商品"的点位删掉，点位库不会无限膨胀；
- 安全分给得更低（30 vs 用户自建的 40），这样它不会被面交点推荐算法优先推荐。

另外这些设计也在省配额：

- 地图初始化（150 万次/月）和定位（浏览器 `navigator.geolocation`，0 次）才是高频操作，都避开了搜索；
- 选点后的"识别附近地名"走的是 `AMap.Geocoder`（属于基础 LBS，15 万次），不是搜索；
- 导航用高德 URI 跳转（`https://uri.amap.com/navigation`），**完全不消耗任何接口配额**。

回归：
- `node --experimental-strip-types scripts/verify-place-cache.mjs`（13 项：命中/校区隔离/TTL/LRU/坏数据不炸）
- `powershell -File scripts/verify-search-hit.ps1`（10 项：落库 source=3、同名幂等、自建仍 source=2、搜索落库不刷屏、进"我的点位"）

## 七、注意事项

1. **后端报 `90001 系统异常` 先查 MySQL**。排查顺序：
   - `Get-Service MySQL, MySQL80`：本机有**两个** MySQL 服务，项目用的是 **`MySQL`（3306，有 `ershou` 库）**；
     `MySQL80` 指向 **3308**（只有 sakila/world），**不要启动它**，起来只会误导你。
   - `MySQL` 服务已设为**自动启动**（2026-09-30 改），开机不用手动拉。
   - IDEA 控制台里 `GlobalExceptionHandler: 系统异常` 只是标题，**真实堆栈在下面几十行**；
     也可以在返回体里拿 `traceId` 去日志里搜。
   - Hikari 懒初始化，所以 MySQL 没起时**应用照样启动成功**，第一次请求才炸。
2. **dev server 必须用 `localhost` 访问，不能用 `127.0.0.1`**。这台机器上 vite 只绑了 IPv6 回环 `::1`，
   用 `http://127.0.0.1:5173` 会「无法连接到远程服务器」。
3. **点导航/页面"无响应" = 十有八九是 vite 模块缓存发霉**（实测踩过）。
   现象：`消息` / `购物车` / `发布闲置` 三个页面点了没反应，页面空白，控制台报
   `does not provide an export named 'xxx'` 或 `Failed to fetch dynamically imported module`。
   原因：改动像 `api/map.ts` 这种**被大量懒加载路由共享的模块**时，vite 的转译缓存/预构建依赖图
   有时不会正确失效，浏览器仍拿着旧模块。
   处理：
   ```powershell
   # 1) 停掉 dev server，删掉预构建缓存，重启
   Remove-Item -Recurse -Force ershou-web\node_modules\.vite
   cd ershou-web; npm run dev
   # 2) 浏览器必须硬刷新：Ctrl+Shift+R（普通 F5 不够，模块图还压在内存里）
   ```
   自查是否属于这一类（不依赖浏览器渲染）：
   ```powershell
   curl.exe -s http://localhost:5173/src/api/map.ts | Select-String 'export function'
   # 缺了刚写的函数 = 缓存陈旧，按上面重启
   ```
   反面教材：本项目排查时先用 `--dump-dom` 判断"页面是否渲染"，结果 SPA 在该环境下恒为空壳，
   得出两次错误结论。**判据要选不依赖渲染的**（模块导出、HTTP 状态、生产构建）。
4. **PowerShell 脚本必须带 BOM**（本机 PS 5.1 默认按 GBK 读 .ps1，中文会乱码报错），详见环境清单 4.6。
4. Redis / MinIO / Elasticsearch 容器都是 **restart=no 手动启动**，开机后需要跑 `dev-up.ps1`（ES 首次启动约 30~60 秒）。
5. 代卖（毕业季寄卖）为 V1.2 规划，代码尚未实现，数据库表 `consign_order` 已预留。
6. 私聊已落地（WebSocket + 风控 + 未读）；仍待实现的是：举报工单、评价、通知中心、拉黑/隐私设置（P1）、客服介入会话（P1）。
