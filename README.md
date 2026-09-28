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

登录、首页（搜索/分类/排序/分页 + 商品卡片**浏览量徽标**）、**校内地图**（Leaflet CRS.Simple + 气泡聚合 + 选点）、商品详情、发布（多图上传）、购物车（分组 + 合并面交）、我的面交单（买家/卖家双 tab、核销）、我的收藏、我的发布、**商品详情（约面交直接下单：选面交点 + 时间窗，面交点下拉可输入搜索）**、**个人中心（含编辑资料：点头像或按钮打开弹窗，改昵称/头像/学校校区/小铺，头像走 MinIO 单图上传）**、**消息（私聊：会话列表 + 聊天窗 + 快捷话术 + 未读红点，断线指数退避重连）**。

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

## 六、注意事项

1. **PowerShell 脚本必须带 BOM**（本机 PS 5.1 默认按 GBK 读 .ps1，中文会乱码报错），详见环境清单 4.6。
2. Redis / MinIO / Elasticsearch 容器都是 **restart=no 手动启动**，开机后需要跑 `dev-up.ps1`（ES 首次启动约 30~60 秒）。
3. 代卖（毕业季寄卖）为 V1.2 规划，代码尚未实现，数据库表 `consign_order` 已预留。
4. 私聊已落地（WebSocket + 风控 + 未读）；仍待实现的是：举报工单、评价、通知中心、拉黑/隐私设置（P1）、客服介入会话（P1）。
