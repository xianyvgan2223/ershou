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

## 目录

```
lib/
  main.dart                     入口（SharedPreferences + ProviderScope）
  app.dart                      MaterialApp；登录态决定首屏（连接页 / 登录页 / 首页）
  core/
    config/server_config.dart           地址模型：origin / apiBase / 图片重写 / ws 地址
    config/server_config_service.dart   持久化 + 连通性探测
    discovery/lan_scanner.dart          一键连接：广播 → 网段扫描 → 结果流
    network/api_client.dart             dio 封装：统一响应体、错误码、Bearer
    storage/local_store.dart            token / 用户快照
    providers.dart                      Riverpod providers、登录态、列表状态
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

1. **发布商品**：`POST /api/v1/items` + `POST /api/v1/files/upload`（multipart，字段 `file`，`biz=item`），image_picker 依赖已装
2. **收藏 / 购物车 / 面交单**：`/api/v1/favorites`、`/api/v1/cart`、`/api/v1/meetup-orders`
3. **私聊**：`ws://<host>:8080/ws/chat?token=<token>`（`ServerConfig.chatWs()` 已拼好），REST 兜底 `/api/v1/chat`
4. **校内地图**：`/api/v1/map`，底图是 CRS.Simple 自定义图，手机端要么用 flutter_map，要么自绘 CustomPainter

## 已知注意点

- Android 已开 `usesCleartextTraffic`（局域网走明文 HTTP，Android 9+ 默认禁止）
- 权限：INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CAMERA（扫码）
- Gradle wrapper 指向腾讯云镜像（`services.gradle.org` 在校园网下连不上，会卡在下载 9.1.0）
- `mobile_scanner` 需要相机权限；拒绝授权时扫码弹窗会显示可读错误，不影响手输 IP
