# 校园二手 · 手机端（Flutter / Android）

对接同仓库的 Spring Boot 后端 `ershou-server`，**局域网直连**：不走公网、不涉及支付、不需要上架。

## 为什么有「连接服务器」这一页

校园网 IP 会变（今天 10.34.29.94，明天可能换一个），所以 App 不写死地址，提供三条路：

| 方式 | 说明 |
| --- | --- |
| **一键扫描** | 先 UDP 广播探测（后端 `LanDiscoveryResponder` 监听 **48899**，回包地址就是电脑 IP，最快）；没回包就按手机网卡自动推出 /24 网段，并发探测 8080，并用 `/api/v1/categories` 校验「真的是本项目后端」 |
| **手动输入** | 填 IP + 端口（默认 8080），连接前会先验证 |
| **扫码** | 右上角扫码，内容支持 `10.34.29.94:8080`、`http://10.34.29.94:8080` 或 `{"host":"...","port":8080}` |

冷启动会先探测上次用过的地址，能通就直接进登录页。

## 跑起来

```powershell
# 1) 电脑端：依赖 + 后端（后端要监听 0.0.0.0，局域网才访问得到）
.\scripts\dev-up.ps1
.\scripts\dev-server.ps1

# 2) 查电脑 IP（手机要连的就是它）
Get-NetIPAddress -AddressFamily IPv4 -InterfaceAlias WLAN | Select-Object IPAddress

# 3) 手机端
cd ershou_app
flutter devices          # 确认手机在线（offline 就在手机上点「允许 USB 调试」）
flutter run
```

### 校园网连不上（实测：GUET-WiFi 有 AP 隔离）

手机和电脑在同一个 /17 网段（手机 10.34.35.171、电脑 10.34.29.94）**也连不通**，
因为校园 WiFi 默认开了**客户端隔离**（同网设备互相不可见，防火墙放行也没用）。两条现实可行的路：

**A. 让电脑连手机热点（推荐，最稳）**

手机开热点 → 电脑连该热点 → 电脑 IP 变成 `192.168.43.x` → App 里点「一键扫描」直接找到。

**B. USB 隧道（adb reverse，不用改网络）**

```powershell
adb reverse tcp:8080 tcp:8080
```

这会把手机自己的 `127.0.0.1:8080` 映射到电脑的 8080，App 的「一键扫描」会**先探测这条隧道**（显示「USB 隧道」），
第二条路就是它。实测有效（在电脑上起一个测试 HTTP 服务，手机 curl 能拿到响应）。
缺点：每次插拔 USB / 重启 adb 后要重新执行一次。

### 其他排查顺序


1. 手机浏览器打开 `http://<电脑IP>:8080/api/v1/categories`：能出 JSON 说明网络通，问题在 App；
2. 出不来 → 电脑防火墙放行 8080（UDP 48899 是扫描用的）；
3. 校园网常开 **AP 隔离**（同网设备互相不可见）。绕过办法：**手机开热点、电脑连手机热点**，
   这时电脑 IP 一般是 `192.168.43.x`，一键扫描能直接找到。

## 导航结构

```
底部导航：首页 | 消息 | (＋发布) | 地图 | 我的        ← 4 个 tab 用 IndexedStack 保活
```

| 入口 | 内容 |
| --- | --- |
| 首页 | 搜索 / 分类 / 排序 / 双列瀑布流 → 商品详情 |
| 消息 | 会话列表（未读红点、我买/我卖、在线状态）→ 聊天窗（文本 + 图片 + 快捷话术 + 风控提示） |
| ＋ | 发布闲置：多图上传 → 标题/描述/价格/分类/成色/议价/库存/面交点 |
| 地图 | 校内点位图（自绘底图 + 价格标记）→ 点位商品列表 |
| 我的 | 资料卡 / 信用分·发布额度·待办面交 / 我的收藏·我的发布·我的面交 / 服务器地址·切换账号·退出 |

商品详情页底部：**收藏**（真实接口）、**私聊**（自动建/复用会话）、**约面交**（创建面交单）。

## 已实现

- 服务器地址管理：持久化、历史地址、冷启动自动探测上次地址
- 局域网发现：UDP 广播 + 网段扫描 + 手动输入 + 扫码
- 登录：手机号 + 验证码（开发环境固定 `123456`）
- 商品列表：分类筛选、排序（最新 / 价格升降）、分页加载、下拉刷新、ES 关键词搜索
- 商品详情：多图轮播、价格、状态、卖家信息、描述、建议面交点
- 接口层：Sa-Token `Authorization: Bearer`、统一响应体拆包（`code != 0` 抛 `ApiException`）
- **图片地址重写**：后端返回的 MinIO 绝对地址（`http://127.0.0.1:9010/ershou/...`）自动换成当前服务器地址
  —— 手机访问 127.0.0.1 会指向手机自己，这一步不做图片全裂
- **私聊**：全局单例 WebSocket（30 秒心跳 / 半死连接重连 / 切后台回来重连）+ 本地消息镜像 + 撤回 + 已读回执 + 历史翻页 + 失败重发 + 草稿，详见下一节
- **本地缓存**：聊天会话/消息（sqflite）、商品详情首屏、分类列表、用户快照、图片磁盘缓存（800 个文件 / 60 天，可在「我的」里清理）

## 目录

```
lib/
  main.dart                     入口（SharedPreferences + ProviderScope）
  app.dart                      MaterialApp；登录态决定首屏（连接页 / 登录页 / 首页）
  core/
    config/server_config.dart           地址模型：origin / apiBase / 图片重写 / ws 地址
    config/server_config_service.dart   持久化 + 连通性探测
    discovery/lan_scanner.dart          一键连接：广播 → 网段扫描 → 结果流
    network/api_client.dart             dio 封装：统一响应体、错误码、Bearer、10002 回调
    network/chat_events.dart            WS 帧解析（{type,data} 信封）
    network/chat_socket.dart            私聊长连接：心跳、退避重连、帧流
    storage/local_store.dart            token / 用户快照 / 小对象 JSON 缓存
    storage/chat_database.dart          聊天镜像库建表与迁移（可丢，失败即重建）
    storage/chat_dao.dart               会话/消息读写 + 行映射
    storage/chat_cache.dart             领域层：scope、合并规则（纯函数，有单测）
    storage/image_cache.dart            图片磁盘缓存配置与清理
    app_lifecycle.dart                  全局保活 + 回前台重连
    providers.dart                      Riverpod providers、登录态、列表状态、聊天事件路由
  data/
    models.dart                   Item / Category / Seller / AppUser / PageResult
    api_repository.dart           AuthRepository / ItemRepository
  ui/
    pages/connect_page.dart       连接服务器（扫描 / 手动 / 扫码 / 历史）
    pages/login_page.dart         登录
    pages/home_page.dart          商品列表
    pages/item_detail_page.dart   商品详情
    widgets/net_image.dart        带地址重写的网络图片
```

## 私聊链路：WebSocket 协议与本地缓存

### 服务端帧是信封，不是裸消息

所有推送统一是 `{"type":"...","data":{...}}`（后端 `ChatPushService.buildPayload`）：

| type | data | 手机端动作 |
| --- | --- | --- |
| `ready` | `{userId, online, instanceId}` | 标记在线、刷未读、用 `afterId` 增量补拉断线期间的消息 |
| `pong` | `{ts}` | 心跳存活 |
| `message` | ChatMessageVO（接收方视角） | 写库 + 增量渲染 + 未读（人就在这个会话则直接标已读） |
| `ack` | ChatMessageVO（带 `clientMsgId`） | 把"发送中"替换成服务端消息 |
| `recall` | ChatMessageVO（`recalled=true`） | 覆盖成撤回态（原文不再留存） |
| `read` | `{sessionId, readerId}` | 自己最后一条显示"已读" |
| `read-ack` | `{sessionId}` | 可忽略（本地已乐观置零） |
| `error` | `{clientMsgId, code, message}` | 标失败 + 弹后端文案（风控/限额/无权） |

> 踩过的坑（v3.2 及以前）：手机端把整帧当消息体解析（读 `raw['id']`/`raw['sessionId']`），
> 信封里没有这两个字段 → `id == 0` 被当成非法帧丢弃，`message/ack/recall` 一个都收不到；
> 而"连上 WS 就停轮询"让这个 bug 表现为**显示"在线 · 实时接收"却收不到消息，必须退出重进**。
> 现在协议解析被 `test/chat_protocol_test.dart` 锁死。

### 连接策略（对齐 Web 端 `ershou-web/src/stores/chat.ts`）

- **全局单例**：`chatSocketProvider`，登录后自动连、退出登录/换服务器自动断（以前是每个聊天页各建一条，退出即断）
- **心跳 30 秒**：服务端在线状态是 90 秒 TTL（`ChatPushService.touch`），不 ping 的话你在对方列表里 90 秒后就变灰
- **半死连接**：75 秒收不到任何帧就主动重连；退避 1/2/4/8 秒
- **回前台重连**：`ChatConnectionKeeper`（`core/app_lifecycle.dart`）监听生命周期，resumed 时重连 + 校准未读
- **轮询兜底**：判断条件从"没连上"改成"**没连上，或在线但 20 秒没收到帧**"，避免上面的坑复发
- **会话列表实时**：新消息/撤回/重连会 bump `chatRevisionProvider`，列表 600ms 去抖后自动刷新（以前要下拉或退出重进）

### 本地缓存（sqflite）

- 库文件 `<App 私有目录>/ershou_chat.db`：纯缓存，随时可丢
- 命名空间 `scope = <服务器 origin>|<用户 id>`：换服务器、切账号天然隔离，不串会话
- 表：`chat_session`（列表首屏）、`chat_message`（消息镜像）、`chat_sync`（每会话最大消息 id，补拉用）
- 消息主键：服务端消息 `s:<id>`、本地乐观消息 `c:<clientMsgId>` —— 收到 ack 就是一次主键覆盖，不靠时间猜
- 合并规则（`mergeMessages`，纯函数 + 单测）：服务端窗口为准做 upsert；**本地没回包/发送失败的行保留**；
  不能因为"服务端没返回"就删本地行（服务端会把被风控拦截的消息对接收方隐藏）
- 读路径：进页面先渲染本地（秒开）→ 网络回来合并写回；断网时顶部提示"当前网络不可用，下面是本地缓存的消息"
- 发送中超过 30 秒没回包 → 自动标"发送失败，点击重发"
- 保留与清理：每会话留最近 300 条（`sending`/`failed` 永不裁）；退出登录、token 失效（10002）时清当前 scope
- 单测说明：本机离线环境下 `sqlite3` 原生库需要联网下载，所以测试环境不跑真库 ——
  DAO 只留极简 SQL，所有可测逻辑（帧解析、合并规则、scope）都抽成纯 Dart 函数（`test/chat_merge_test.dart`）

### 后端配套（v3.3）

- `GET /chat/sessions/{id}/messages` 增加 `afterId`：断线补拉只取比它新的（与 `beforeId` 互斥，同时传 PARAM_ERROR）。
  手机端在收到 `ready` 帧（重连成功）时，用本地最大消息 id 调它；返回满一页或后端不认识这个参数时，回退全量刷新
- `ChatSessionVO.lastMsgId`：客户端据此判断"这个会话有没有新消息"，不必每次都全量拉消息
- 回归：`scripts/chat-smoke.mjs` 增加了 6 项断言覆盖 `afterId` / `lastMsgId`（旧后端会失败 5 项，属预期）

### 真机验收清单

1. 手机停在聊天页，Web 端发消息 → 1 秒内出现，顶部显示"在线 · 实时接收"
2. 关掉电脑 WiFi 5 秒再恢复 → 状态回到在线，断线期间的消息自动补齐
3. 飞行模式进聊天页 → 有缓存则直接出内容 + 离线提示；发消息 → "发送失败，点击重发"，点一下能重发
4. 长按自己 2 分钟内的消息 → 撤回，两边同步变成"你撤回了一条消息"
5. 滚到顶部 → 加载更早历史；对方进页面后自己最后一条显示"已读"
6. 杀进程重进 → 会话列表与消息仍在；退出登录 → 缓存清空（换账号看不到上一个账号的会话）
7. "我的 → 清理图片缓存" → 提示已清理，再逛商品图重新下载

## 日常改代码：热重载（重要）

**不要每次改完都 `flutter build apk` + 安装**，用热重载：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\dev-run.ps1
# 然后在这个窗口里：r = 热重载（保留页面状态）  R = 热重启  q = 退出
```

| 改了什么 | 怎么生效 |
| --- | --- |
| 页面布局 / 文案 / 颜色 / 大部分逻辑 | **r** 热重载，1 秒内手机上就变了 |
| `initState` / 全局变量 / `main()` | **R** 热重启 |
| `pubspec.yaml` 依赖、`AndroidManifest.xml`、gradle 配置 | 只能停掉重新 `flutter run` |

> 踩过的坑：debug 包里虽然有 Dart VM service，但**只连 VM service 调 `reloadSources` 是无法热重载的**
> （报 `Error while starting Kernel isolate task`）——重编译要靠 `flutter run` 常驻的 kernel 编译器，
> 所以必须由 `flutter run` / `flutter attach` 持有会话。IDE（VS Code / Android Studio）里按快捷键也是这个道理。

## 测试

```powershell
flutter test                            # 单元测试（离线可跑）
flutter test test/lan_probe_test.dart   # 集成测试：需要后端已启动，会真打 127.0.0.1:8080
```

集成测试覆盖：探测真实后端、局域网 IP 可达性、错误地址判定、`LanScanner.tryManual`、
统一响应体拆包、商品列表/详情解析 + 图片地址重写。
连不上 127.0.0.1 的环境（沙箱、防火墙）会自动 skip 而不是失败。

## 卡片上的收藏星（和网页端一致）

首页 / 点位页 / 收藏页的每张卡片，封面**右上角**都有一个五角星（可议价角标在左上角，不冲突）：

| 状态 | 外观 |
| --- | --- |
| 未收藏 | 白色圆形底 + 灰色描边星 |
| 已收藏 | 白色圆形底 + 橙色实心星（带 150ms 缩放动画） |
| 未登录 | 点一下提示「登录后才能收藏商品」，不发请求 |
| 请求失败 | 星星自动回滚到原状态并弹后端文案（不会留下假收藏态） |

实现要点：

- 星星是卡片的**兄弟节点**（`Stack(card, Positioned(star))`），不是嵌在卡片的 InkWell 里 —— 否则两个手势识别器抢同一个点击，点星星边缘会穿透到卡片上跳详情页。
- 点击热区 34×34，视觉尺寸 28（手指不好点 28）。
- 乐观更新：先改 UI 再发请求；成功后调 `itemFeedProvider.setFavorited()` 同步列表状态，从详情页返回也不会闪回旧值。
- 回归测试：`test/item_card_star_test.dart`（4 项：收藏 / 取消 / 失败回滚 / 未登录不发请求），用假 dio 适配器记录请求，不再依赖手点。

## 商品编辑（在售 / 已下架都能改）

| 端 | 入口 | 说明 |
| --- | --- | --- |
| 移动端 | 我的 → 我的发布 → 卡片右侧「编辑」 | 复用发布页（`PublishPage(editing: item)`）：表单预填，**只提交改动过的字段** |
| 网页端 | 我的发布 → 卡片右侧「编辑」 | Element Plus 弹窗，改标题 / 价格 / 原价 / 成色 / 描述 |
| 后端 | `PUT /api/v1/items/{id}` | `ItemUpdateDTO` 字段全可选，null = 不动这一项 |

后端守卫（都实测过）：

| 场景 | 返回 |
| --- | --- |
| 只改价格 | `code:0`，标题和描述原样保留 |
| 改标题 / 描述 | 重新过机审；命中禁售词 `50001` 且**标题不变** |
| 改别人的商品 | `30005 只能操作自己发布的商品` |
| 改已售出的商品 | `30002`（留着成交记录） |
| 商品不存在 | `30001` |
| 价格 0.001 | `10001 价格至少 0.01 元` |

改完会 `TxHooks.afterCommit` 同步 ES 索引 + 清 Redis 详情缓存，列表和详情立刻看到新值。

## 尺寸体系（改大小只动这几个地方）

| 位置 | 令牌 | 当前值 |
| --- | --- | --- |
| 全局字号 | `app.dart` 的 `MediaQuery.textScaler` | **×0.9**（所有页面文字一起变小，系统字体设置仍叠加生效） |
| 顶部栏高 | `AppSpacing.appBarHeight` | 42 |
| 底部栏高 | `AppSpacing.navBarHeight` | 44 |
| 卡片内边距 | `AppSpacing.cardPadding` | 11 |
| 页面边距 / 卡间距 | `AppSpacing.page` / `gap` | 9 / 8 |
| 卡片圆角 / 输入框圆角 | `AppRadius.card` / `field` | 13 / 11 |
| 输入框字号与内边距 | `inputDecorationTheme` | 13 / 14×12，`isDense: true` |
| 商品卡比例 | 首页 / 点位页网格 | `childAspectRatio: 0.60`（容下两行标题的实测值） |

改字号不用去几十个文件里翻 `fontSize`：动 `app.dart` 里那个 `0.9` 就能整体再收/放一档。

## 两个必须记住的坑（都已修）

### 1. 图片在 MinIO 的 9010，不在后端的 8080

后端 `public-base-url` 是 `http://127.0.0.1:9010/ershou`，接口在 8080。
`ServerConfig.resolveAssetUrl()` 重写图片地址时**只换主机名、必须保留端口**：

```dart
// 配 host=192.168.17.112（8080 是 API）
resolveAssetUrl('http://127.0.0.1:9010/ershou/a.jpg')
// => http://192.168.17.112:9010/ershou/a.jpg   ✅
// 曾经错误地变成 http://192.168.17.112:8080/ershou/a.jpg
//    8080 上这个路径返回 {"code":90001} 的 JSON，图片全裂
```

实测对照：`8080/ershou/x.jpg` → `application/json` 92B；`9010/ershou/x.jpg` → `image/jpeg` 279910B。
回归测试锁在 `test/widget_test.dart`。

### 1.5 底部导航必须放在 Column 里，不能塞 Scaffold 的 bottomNavigationBar 槽位

用槽位时 Scaffold 把 body 高度算成「整屏 − 底栏」，再叠加 SafeArea 的内边距，
网格按比例算出的卡片高度就被截断——现象是**最后一行只剩一条边**（真机踩到过）。
现在 `shell_page.dart` 用 `Column(Expanded(IndexedStack), _navBar())` 自己排版，高度就准了。

### 2. 商品卡片高度溢出（BOTTOM OVERFLOWED BY n PIXELS）

1:1 封面 + 两行标题 + 价格行 + 卖家行 ≈ 273px，而 2 列网格在 720 宽屏上按比例只给 260px。
现在 `childAspectRatio: 0.58` 并压缩了内边距/行高，真机实测不再溢出。

## 后端配套改动

- `common/discovery/LanDiscoveryResponder.java`：UDP 48899 应答，实现「一键连接」的广播发现。
  端口被占用时只打警告，不影响后端启动。**改完要重启后端才生效。**

## 接着做什么（按依赖顺序）

1. ~~**发布商品**：`POST /api/v1/items` + `POST /api/v1/files/upload`~~ ✅ 已完成（含编辑：只提交改动字段）
2. ~~**收藏 / 面交单**：`/api/v1/favorites`、`/api/v1/meetup-orders`~~ ✅ 已完成
3. ~~**私聊**：`ws://<host>:8080/ws/chat?token=<token>`~~ ✅ 已完成（长连接 + 本地缓存，见「私聊链路」一节）
4. **校内地图**：`/api/v1/map`，底图是 CRS.Simple 自定义图，手机端要么用 flutter_map，要么自绘 CustomPainter
5. **待办**：离线自动重发队列（当前只做失败手动重发）、消息图片上传进度、推送（APNs/FCM）

## 已知注意点

- Android 已开 `usesCleartextTraffic`（局域网走明文 HTTP，Android 9+ 默认禁止）
- 权限：INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CAMERA（扫码）
- Gradle wrapper 指向腾讯云镜像（`services.gradle.org` 在校园网下连不上，会卡在下载 9.1.0）
- `mobile_scanner` 需要相机权限；拒绝授权时扫码弹窗会显示可读错误，不影响手输 IP
