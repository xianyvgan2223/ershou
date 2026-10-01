# 校园二手交易平台

面向高校场景的二手交易信息撮合平台：**轻注册（不实名）+ 校内地图面交 + AI 内容审核**。
平台只做信息撮合与监管，**不介入交易资金**（面交线下自行支付）。

| 模块 | 目录 | 技术栈 |
| --- | --- | --- |
| 需求文档 | `校园二手交易平台需求文档.md` | PRD（含校内地图、AI 审核、毕业季代卖） |
| 开发文档 | `开发文档.md` | 架构、数据库、Redis、**Elasticsearch 搜索**、接口、部署、排期 |
| 开发日志 | `开发日志.md` | 每轮迭代的产出 / 决策 / 踩坑 / 实测验证（倒序，最新在最上面） |
| 环境清单 | `环境检查与安装清单.md` | 本机环境实测结果与安装步骤 |
| 后端 | `ershou-server/` | Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 + Sa-Token + MinIO + Elasticsearch 8 |
| 前端 | `ershou-web/` | Vue 3.5 + Vite 6 + TypeScript + Element Plus + Leaflet |
| 数据库 | `sql/schema.sql` | MySQL 8，19 张表 + 种子数据 |
| 依赖编排 | `docker-compose.yml` | Redis（6380）+ MinIO（9010/9011）+ Elasticsearch（9210），手动启动 |

---

## 一、本地环境（已实测就绪）

| 组件 | 地址 | 说明 |
| --- | --- | --- |
| MySQL | 127.0.0.1:3306 | 服务名 **MySQL**（不是 MySQL80，那个是 3308），库 `ershou`，账号 `ershou` |
| Redis | 127.0.0.1:**6380** | 容器 `ershou-redis`，避开青光眼项目的 6379 |
| MinIO | API **9010** / 控制台 **9011** | 容器 `ershou-minio`，桶 `ershou` 由后端启动时自动创建 |
| Elasticsearch | http://127.0.0.1:**9210** | 容器 `ershou-es`，商品搜索索引 `ershou_item`（后端启动自动建索引+全量同步） |
| 后端 | http://127.0.0.1:8080 | 接口文档 /swagger-ui.html |
| 前端 | http://localhost:5173 | /api 代理到 8080 |

**所有密码集中在根目录 `.env`（已 gitignore）**，`.env.example` 是模板。

## 一·五、手机端（Flutter）局域网联调

| 位置 | 说明 |
| --- | --- |
| `ershou_app/` | Flutter Android 客户端（详见 `ershou_app/README.md`） |
| `scripts/lan-info.ps1` | 打印手机该连的 IP + 自测 8080，换网络后跑一下 |
| `LanDiscoveryResponder` | 后端 UDP **48899** 应答，手机「一键扫描」靠它秒连（**需重启后端生效**） |

```powershell
# 手机端连不上时的排查顺序（校园网 IP 会变、可能开 AP 隔离）
powershell -ExecutionPolicy Bypass -File .\scripts\lan-info.ps1
```

App 里提供三条连接路径：**一键扫描**（UDP 广播 → 网段扫描）、**手动输入 IP:8080**、**扫二维码**；
冷启动会先探测上次用过的地址，能用就直接进登录页。

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

其他脚本：`dev-down.ps1`（停依赖）、`check-env.ps1`（环境自检）。

> ES 镜像默认由 `docker/es/Dockerfile` 构建，已内置 **IK 中文分词**（装不上会自动退官方 smartcn，再不行用内置 ngram —— 后端启动时探测实际可用的分词器再建索引）。
> 想用纯官方镜像：删掉 `docker-compose.yml` 里 elasticsearch 的 `build` 段、把 image 改回 `elasticsearch:8.13.4`，然后删除旧索引重启后端即可。

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
Invoke-WebRequest -Method Post http://127.0.0.1:9210/ershou_item/_reload_search_analyzers

# 3b) 改了 custom.dic（新增词）：必须重建索引，文档切分才会用上新词
Invoke-WebRequest -Method Delete http://127.0.0.1:9210/ershou_item   # 重启后端会自动重建
```

> 两条硬性规则（都是踩过的坑）：① 同义词每个条目必须是**一个词** —— 简称要先写进 `custom.dic`，否则 IK 把「高数」切成「高|数」，规则永远不命中；② 不要写多词短语（如「平板电脑」「移动电源」），同义词的值会再走一遍分词，把子词带进查询造成过度扩展（搜「平板」会连带搜到「电脑」）。

## 三、已实现功能

### 后端（137 个 Java 文件 / 66 个接口）

| 模块 | 能力 |
| --- | --- |
| 认证 | 手机号 + 验证码登录（轻注册，**无实名/学籍字段**）；手机号 AES 加密存储 + SHA256 索引；新号 7 天内限额；**个人资料编辑**（`PATCH /users/me`：昵称/头像/学校校区/小铺名与小铺简介，昵称与小铺名过内容审核，头像只收上传接口产出的地址） |
| 商品 | 发布（含 AI 机审）/ 详情 / 列表 / 搜索筛选排序 / 下架 / 我的发布 |
| **搜索** | Elasticsearch 8 索引 `ershou_item`：中文分词（IK→smartcn→ngram 自动探测）、`title^3` 相关性排序、`<em>` 高亮；ES 挂掉或深分页自动降级 MySQL LIKE；索引随写路径实时同步 + 2 分钟增量兜底 + 管理员全量重建 |
| **AI 审核** | 禁售词直接拦截（`50001`）、可疑词转人工（状态"审核中"）；接口抽象，可换 Ollama 或云审核 |
| **校内地图** | 底图配置 + 点位（双坐标）+ 每个点位在售数/最低价 + 面交点推荐 + 用户自定义点位提交 |
| 收藏 | 收藏/取消（唯一索引兜底并发重复） |
| 购物车 | 按卖家分组、增删改、**合并面交结算**（同卖家合并、共享行程号 batchNo） |
| 面交单 | **约面交 = 意向单**（提交不锁库；面交点/时间窗都可留空 = 待协商，提交后自动私聊提醒卖家）→ 卖家接受（这时才条件更新锁库，锁到时间窗结束 +2h）→ 见面验货 → 卖家点「已卖出」成交（商品售出 + 双方信用分 +2）；核销码已删除 |
| **私聊 IM** | WebSocket `/ws/chat`（Sa-Token 握手鉴权）+ REST 兜底：从商品发起会话（唯一索引复用）、文本/图片消息、未读数与已读回执、快捷话术、对方在线状态；**买卖角色区分**（会话列表显示"我买/我卖"，气泡左侧对方头像+「昵称（卖家）」，顶部标明"我是买家/卖家"）；**消息撤回**（仅发送方、限时 2 分钟、双向实时消失、会话预览同步改「已撤回」）；**拉黑**（单向存储双向生效：任一方拉黑则双方都发不出，会话与主页都有状态提示）；**用户主页**（`/user/{id}`：信用/学校/在售，可私聊可拉黑，公开接口不含任何联系方式）；**聊天风控**：高危词拦截并留痕（独立事务，证据不随发送失败回滚）、可疑词弱提醒不篡改原文、新号每日私聊限额；正文 AES-GCM 加密落库；跨实例 Redis Pub/Sub 广播 |
| 文件 | MinIO 上传（校验 → 压缩长边 1280 → 存桶），MinIO 不可用时可切 `local` 降级 |
| 定时任务 | 释放超时锁库、面交单 2 小时超时失效、商品 60 天过期、**浏览量每 10 分钟批量回写** |
| **运营后台** | 审核工作台（商品 / 点位 通过·驳回·批量）、模板化驳回原因、商品全状态检索与**强制下架**、用户封禁/解封/限权、信用分人工调整（写 credit_log 流水）、操作审计（sys_audit_log，含变更前后与 IP）；管理员判定统一走 role_flags & 8 或白名单 |
| **Redis 读缓存** | 商品详情（10 分钟 + 空值缓存防穿透）、地图点位含在售数/最低价（30 分钟，版本号 O(1) 整体失效）、分类/学校/校区/点位字典（1 小时，MGET 消 N+1）、运营概览 8 个 count（30 秒）；浏览量走 Redis 计数 + 每 10 分钟批量回写；失效统一在 `TxHooks.afterCommit` 执行；`ershou.cache.enabled=false` 一键回滚 |
| 通用 | 统一响应体 + 错误码分段 + 全局异常 + traceId 链路 + 分页 + Swagger |

### 前端（18 个页面 / 49 个源文件）

登录、首页（搜索/分类/排序/分页 + 商品卡片**浏览量徽标**）、**校内地图**（双模式：`map_type=1` Leaflet CRS.Simple 自绘平面图 / `map_type=2` **高德 JS API 真地图 + 定位蓝点 + 米制距离**）、商品详情、发布（多图上传）、购物车（分组 + 合并面交）、我的面交单（买家/卖家双 tab、核销）、我的收藏、我的发布、**商品详情（约面交直接下单：选面交点 + 时间窗，面交点下拉可输入搜索）**、**个人中心（含编辑资料：点头像或按钮打开弹窗，改昵称/头像/学校校区/小铺，头像走 MinIO 单图上传）**、**消息（私聊：会话列表 + 聊天窗 + 快捷话术 + 未读红点，断线指数退避重连）**。

**运营后台**（/admin，仅运营可见，路由 requiresAdmin 守卫）：运营概览、审核工作台（商品/点位双 tab + 批量处置 + 驳回原因模板）、商品管理（强制下架）、用户管理（封禁/解封/信用分）、操作日志。

## 四、关键验证结果

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

前端顶栏下拉与"我的"页会在 isAdmin=true 时出现**运营后台**入口；接口前缀 /api/v1/admin（16 个接口）。

| 能力 | 关键接口 |
| --- | --- |
| 概览与待办 | GET /admin/dashboard |
| 商品审核队列 | GET /admin/audit/items |
| 审核通过 / 驳回 / 批量 | POST /admin/audit/items/{id}/approve · /{id}/reject · /batch |
| 驳回原因模板 | GET /admin/audit/reject-reasons?scope=item 或 point |
| 点位审核 | GET /admin/audit/points · POST /admin/audit/points/{id}/approve 或 reject |
| 商品管理 / 强制下架 | GET /admin/items · POST /admin/items/{id}/offline |
| 用户管理 / 信用分 | GET /admin/users · POST /admin/users/{id}/status 或 credit |
| 面交单查询 | GET /admin/orders |
| 操作审计 | GET /admin/audit-logs |

**端到端自测**：scripts/admin-smoke-test.ps1（默认打 8080，可加 -Base http://127.0.0.1:8081/api/v1 指向其他实例；44 项断言，可重复执行）。
**私聊自测**：scripts/chat-smoke-test.ps1（同样支持 -Base，会先清空 chat 表与私聊 Redis key，可重复执行；依赖本机 node，脚本用 node 内置 fetch/WebSocket）。
**资料编辑自测**：scripts/profile-smoke-test.ps1（同样支持 -Base，用测试号 13800000003，跑完会把资料还原成默认值，可重复执行）。

## 六、高德地图接入（校内地图真地图模式）

校内地图是**双模式**的，由后端 `campus.map_type` 决定前端用哪个组件，两者对外 props/events 完全一致，业务代码无感切换：

| map_type | 前端组件 | 说明 |
| --- | --- | --- |
| 1 | `CampusMap.vue` | Leaflet `CRS.Simple` + 自绘底图（图片或 CSS 网格），坐标为像素 |
| 2 | `AmapCampusMap.vue` | 高德地图 JS API 2.0，真实经纬度、定位蓝点、米制距离、选点即得 lng/lat |

**1) 配置 Key**（已写入仓库根目录 `.env`，该文件不进 Git）：

```ini
VITE_AMAP_JS_KEY=            # 控制台 → 应用管理 → 添加 Key → 服务平台必须选「Web端(JS API)」
VITE_AMAP_JS_SECURITY_CODE=  # 与 key 同页面显示的「安全密钥」，2021-12-02 后创建的 key 强制要求
```

> ⚠️ **两个必须记住的坑**（本项目都踩过）：
> 1. 变量名**必须带 `VITE_` 前缀**。写成 `AMAP_JS_KEY` 时 vite 不会注入浏览器，
>    `import.meta.env.VITE_AMAP_JS_KEY` 是 `undefined`，前端会静默退回自绘平面图，**不报错**。
>    自检：`curl http://localhost:5173/@vite/client` 看不到你的变量 = 前缀写错了。
> 2. **不要用 REST 接口验证 JS API Key**。`restapi.amap.com/v3/security/check` 是给「Web服务」Key
>    做数字签名用的，拿 JS API Key 去打只会返回 `USERKEY_PLAT_NOMATCH (10009)` ——
>    那是"这把 key 不属于我这个平台"的意思，属于**正常反证**，不代表 key 有问题。
>    JS API Key 唯一可靠的验证方式是在浏览器里真的渲染一张地图出来。
> 3. 前端通过 vite 的 `envDir` 读根目录 `.env`（见 `ershou-web/vite.config.ts`），改完 `.env` **必须重启 dev server**。

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

**购物车（`/api/v1/cart`）**

- **加购即校验**：已售出 / 已锁定 / 自己的商品 / 超库存都会被拒（和结算同一套口径），不然前端显示成可结算、到结算才失败，用户白跑一趟；
- **数量夹取**：孤品恒为 1，其余夹在 `[1, 库存]`；
- **结算不会因一件坏商品整批失败**：先按状态分流，能约的照常提交，不能约的逐条回报在 `skipped: [{cartId,itemId,title,reason}]` 里，前端弹窗列出哪件、为什么；
- 拆单语义：**每件商品一张面交单**（同一个卖家的多件也是多张），只是共用一个 `batchNo`；
- 移动端与 Web 端同一套接口与语义；回归：`powershell -File scripts/verify-cart.ps1`（31 项）

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
