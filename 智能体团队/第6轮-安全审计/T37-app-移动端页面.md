# T37 · Flutter 移动端页面与客户端安全审计（只读）

| 项 | 值 |
| --- | --- |
| 任务 | 第 6 轮安全审计 · T37（Flutter 移动端页面与客户端） |
| 审计人 | `app-member`（只读静态审计，未修改任何代码/配置） |
| 审计日期 | 2026-10-21 |
| 审计对象 | `ershou_app/`（Flutter 3.44.3，Android 工程；**仓库内无 iOS 工程**）；`lib/ui/pages/` 全部 17 个页面 + `lib/core/`、`lib/data/` 关键文件 + `android/app/src/main/AndroidManifest.xml`、`android/app/build.gradle.kts`、`pubspec.yaml` |
| 代码基线 | 工作区 `E:\ershou`（HEAD `681a5e5` + 未提交改动；App 代码最新改动 2026-10-18） |
| 包名/构建 | `com.ershou.ershou_app`；debug 包（`flutter build apk --debug`），release 配置存在但用 debug 签名（见 APP-07） |
| 方法 | 静态审计：读 Dart/Android 配置 + 全仓 grep 取证；**未安装/未替换 App、未发攻击请求、未改代码/配置/数据**（纪律要求） |
| 结论 | 共 **18 条**发现：**高 4 / 中 5 / 低 7 / 信息 2**（含 1 条正向结论）。Top3：伪造服务器钓鱼、全链路明文、长期 token 明文落盘 |

> 说明：本报告所有证据均为 `文件:行`（相对 `E:\ershou\ershou_app\`）。"验证状态"均为**静态确认**（代码/配置可复核）；受审计纪律限制，未做动态抓包/攻击复现，动态验证建议见 §6。

---

## 1. 审计范围与方法

### 1.1 覆盖范围

| 类别 | 文件 |
| --- | --- |
| 页面（17） | `lib/ui/pages/{home,chat_list,chat_page,map_page,profile,publish,login,connect,item_detail,cart,favorites,my_items,meetup_orders,items_by_point,point_picker,platform,shell}_page.dart` |
| 核心 | `lib/main.dart`、`lib/app.dart`、`lib/core/providers.dart`、`lib/core/network/api_client.dart`、`lib/core/network/chat_socket.dart` |
| 配置/发现 | `lib/core/config/server_config.dart`、`server_config_service.dart`、`lib/core/discovery/lan_scanner.dart` |
| 本地存储 | `lib/core/storage/local_store.dart`、`chat_database.dart`、`chat_dao.dart`、`image_cache.dart` |
| 数据/仓库 | `lib/data/models.dart`、`api_repository.dart`、`api_repository2.dart`、`platform_repository.dart`、`platform_models.dart` |
| Android | `android/app/src/main/AndroidManifest.xml`、`android/app/src/{debug,profile}/AndroidManifest.xml`、`android/app/build.gradle.kts`、`kotlin/.../MainActivity.kt` |
| iOS | **不存在** `ios/` 目录（无 Info.plist / URL Scheme / ATS 配置，见 APP-17） |
| 依赖 | `pubspec.yaml`（无安全存储、无崩溃上报 SDK） |

### 1.2 方法

- 枚举页面 → 读页面代码 → 沿 `Navigator.push`/底部 Tab 梳理入口与"页面→接口"链路；
- 对 token/本地存储、服务器发现、传输层、Android 组件、WebView、表单校验逐项 grep + 读上下文取证；
- 反向确认"哪些安全项已经做对"（见 APP-18）。

### 1.3 未覆盖 / 限制

- 未做动态抓包、中间人、WebView 注入、`adb backup` 提取等实际攻击验证（纪律禁止；方案见 §6）；
- 第三方插件内部实现（`webview_flutter`/`image_picker`/`mobile_scanner` 原生层）未逐行审计；
- 后端接口鉴权/校验强度不在本报告范围（本报告只说明"客户端不设防，依赖后端兜底"的部分）；
- 当前本机通过 **USB 隧道**（`adb reverse`：手机 `127.0.0.1:8080/9010` → 电脑）联调，App 保存的服务器是 `127.0.0.1:8080`。USB 通道本身只在本机回环，局域网嗅探风险低，但**传输仍是明文 HTTP/WS**，见 APP-02/04。

---

## 2. 页面清单（页面 → 入口/路由 → 主要接口）

| # | 页面（文件） | 入口 / 路由方式 | 主要接口 | 敏感数据或操作 |
| --- | --- | --- | --- | --- |
| 0 | 主壳 `shell_page.dart`(162) | 登录后根页面 `app.dart:40-43` | `GET /chat/unread`（未读数） | 底部 4 Tab + 中间发布浮动按钮（结构固定，未改） |
| 1 | 首页 `home_page.dart`(339) | 主壳 Tab0 | `GET /items`、`GET /categories`；收藏切换 `POST/DELETE /favorites/{itemId}`；搜索即 `/items?keyword=`（无独立搜索页） | 浏览商品、收藏、搜索词 |
| 2 | 消息列表 `chat_list_page.dart`(610) | 主壳 Tab1 | `GET /chat/sessions`、`GET /chat/unread`、`DELETE /chat/sessions/{id}`、`POST /chat/sessions/batch-delete` | 会话列表、对方昵称/头像 |
| 3 | 地图 `map_page.dart`(693) | 主壳 Tab2 | `GET /map/points`、`GET /map/amap-status`、`GET /trade-points`、`GET /map/regeo`；WebView 加载 `GET /map/amap-html`；点气泡 `GET /items?pageSize=50` 本地过滤 | 定位权限、当前位置、点位；WebView |
| 4 | 我的 `profile_page.dart`(269) | 主壳 Tab3 | `GET /users/me`、`GET /meetup-orders/todo-count`；登出 `POST /auth/logout` | 手机号（脱敏显示）、角色标识"运营"、退出登录 |
| 5 | 发布/编辑 `publish_page.dart`(885) | 主壳悬浮按钮 / 我的发布编辑 | `GET /categories`、`GET /trade-points`、`POST /items`、`PUT /items/{id}`、`POST /files/upload`、`POST /trade-points`、`POST /trade-points/search-hit` | 图片选择/上传、标题/描述/价格/库存、面交点 |
| 6 | 登录 `login_page.dart`(262) | 未登录时根页面 `app.dart:40-43` | `POST /auth/sms-code`、`POST /auth/login`；`POST /auth/logout`（登出） | 手机号、验证码、token 写入本地 |
| 7 | 连接服务器 `connect_page.dart`(619) | 登录页"服务器"按钮 `login_page.dart:105`、我的→服务器地址 `profile_page.dart:65-67` | 无业务接口；探测 `GET /categories`；保存服务器地址；扫二维码 | 服务器地址（可被伪造，见 APP-01/09） |
| 8 | 商品详情 `item_detail_page.dart`(754) | 首页/收藏/点位/严选/聊天窗口 push（`home_page:315`、`favorites:87`、`map_point_items_window:93`、`platform_page:277` 等） | `GET /items/{id}`；`POST /meetup-orders`、`POST /cart`、`POST/DELETE /favorites/{id}`、`POST /chat/sessions`；`GET /meetup-orders/active` | 交易/面交意向、收藏、聊天发起 |
| 9 | 购物车 `cart_page.dart`(474) | 详情页购物车入口 `item_detail_page.dart:252` | `GET /cart`、`POST /cart`、`PATCH /cart/{id}`、`DELETE /cart/{id}`、`GET /trade-points`、`POST /cart/checkout` | 购买/结算 |
| 10 | 聊天 `chat_page.dart`(1058) | 消息列表 `chat_list_page:406`、详情"联系卖家" `item_detail_page:157` | `GET /chat/sessions/{id}/messages`、`POST /chat/messages`、`GET /chat/quick-replies`、`POST /chat/sessions/{id}/read`、`POST /chat/messages/{id}/recall`、`POST /files/upload`；WebSocket `ws://host:8080/ws/chat?token=xxx` | 聊天内容、图片、**token 在 WS URL 中**（APP-04）、本地明文落盘（APP-05） |
| 11 | 我的收藏 `favorites_page.dart`(103) | 我的→我的收藏 `profile_page:56-57`；首页顶栏 `home_page:165` | `GET /favorites`、`DELETE /favorites/{id}` | 收藏列表 |
| 12 | 我的发布 `my_items_page.dart`(194) | 我的→我的发布 `profile_page:58-59` | `GET /items/mine`、`POST /items/{id}/offline`、`POST /items/{id}/relist`、`PUT /items/{id}`（编辑） | 我的商品、下架/重上架 |
| 13 | 我的面交 `meetup_orders_page.dart`(436) | 我的→我的面交 `profile_page:60-61` | `GET /meetup-orders?role=`、`POST /meetup-orders/{id}/accept|reject|confirm`、`POST /meetup-orders/{id}/point`、`GET /trade-points` | 面交单状态、时间地点、对方信息 |
| 14 | 点位商品 `items_by_point_page.dart`(101) | 地图页 push `map_page:628` | `GET /items?pageSize=50` 后**本地按点位名过滤**（只取首页 50 条；非安全漏洞，但数据不全/过度拉取） | 商品列表 |
| 15 | 选点 `point_picker_page.dart`(441) | 发布页 `publish_page:825`、面交页 `meetup_orders:252` | `GET /trade-points`、`GET /map/points`、`GET /map/regeo`、`POST /trade-points`、`POST /trade-points/search-hit` | 位置、自定义点位 |
| 16 | 平台严选 `platform_page.dart`(1048) | 首页顶栏金色"严选" `home_page:184` | `GET /platform/shop/{overview,points,items,coupons,flash-sale}`、`POST /platform/me/coupons/{id}/claim`、`POST /platform/me/flash-sale/{itemId}/grab`、`POST /platform/me/items/{id}/buy` | 领券、抢购、直购 |
| 17 | 壳/连接页附加 | — | `activeServerProvider` 全局服务器地址；`ChatConnectionKeeper` 前后台重连（`app_lifecycle.dart`） | 服务器地址、长连接 |

> 说明：App 使用 `Navigator.push`（非命名路由），"路由"即上述入口 push 链；无 Deep Link/URL Scheme（见 APP-18）。

---

## 3. 漏洞表

| ID | 等级 | 页面/组件 | 证据（文件:行） | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **APP-01** | **高** | 服务器发现（连接页） | `lib/core/discovery/lan_scanner.dart:63`（广播端口 48899）、`:93-118`（自动发现顺序：广播→USB→网段）、`:162-211`（UDP 应答只校验 `app=='ershou-server'`，`host` 直接取 `dg.address`）、`:184`；`lib/core/config/server_config_service.dart:58-111`（探测只认 `code==0`，无身份校验）；`lib/ui/pages/connect_page.dart:49-119`（启动自动扫描/自动探测上次地址）、`:157-170`（`_use` 直接保存并 silent 连接） | 同一局域网内任意设备可伪造 UDP 广播应答（或冒充"本项目后端"），App 自动扫描会把它作为候选；用户点击/自动连接后，**登录手机号+验证码、Bearer token、聊天内容全部发往攻击者服务器**（明文 HTTP，见 APP-02）。校园网/公共 WiFi 场景高危 | 静态确认（未动态复现） | ①发现协议加签名/证书校验（服务端私钥签名应答，App 内置公钥验证）；②只允许 HTTPS 候选，探测时校验证书+固定指纹；③候选必须用户显式确认（禁止 auto/silent 使用广播结果）；④USB/手动优先，广播默认关闭；⑤连接前展示服务器指纹（如证书 SHA-256）并记录"信任列表" |
| **APP-02** | **高** | 传输层（全局） | `android/app/src/main/AndroidManifest.xml:15`（`android:usesCleartextTraffic="true"`）；`lib/core/config/server_config.dart:27-28`（`origin` 固定 `http`）、`:33-40`（`chatWs` 固定 `ws`）；`lib/core/network/api_client.dart:66-86`（Dio 无 TLS/证书固定、无`badCertificateCallback`校验）；`pubspec.yaml` 无证书/安全传输相关依赖 | 所有 API、图片、聊天走明文；同一网络（WiFi/热点）可被嗅探/中间人篡改：**Bearer token 可被直接窃取（token 有效期 10 年，见 APP-03）**、聊天内容/图片泄露、响应可被篡改（如伪造登录成功/订单） | 静态确认（未动态复现） | ①服务端启用 HTTPS/WSS，App 默认并强制 https/wss；②加 `network_security_config.xml` 限制明文域（仅调试域白名单）；③证书校验+固定（至少固定服务端证书公钥）；④`usesCleartextTraffic=false`；⑤调试期明文仅允许 USB 回环（127.0.0.1）并显式开关 |
| **APP-03** | **高** | 本地会话存储 | `lib/core/storage/local_store.dart:12`（键 `ershou.token`）、`:19-27`（`setString` 明文写 SharedPreferences）、`:121-125`（`clearSession` 仅删键）；`android/app/src/main/AndroidManifest.xml:14-18`（application 未设 `allowBackup=false`）；`pubspec.yaml:37-56` 无 `flutter_secure_storage`（明文依赖）；服务端有效期：`ershou-server/src/main/resources/application.yml:45`、`ershou-cloud/nacos-configs/ershou-gateway.yaml:34`、`ershou-user-service.yaml:36`（`timeout: 315360000` = 10 年，`active-timeout:-1` 永不过期） | token 明文落盘 + 超长有效期：设备丢失、`adb backup`/云端自动备份、root/恶意取证工具可直接提取 token 并长期冒用；App 删除 token 仅清键，无轮换/撤销机制 | 静态确认（未动态提取） | ①用 `EncryptedSharedPreferences`/`flutter_secure_storage`（Android Keystore）；②服务端缩短 token TTL（如 7~30 天）+ refresh token + 主动撤销/设备绑定；③`android:allowBackup="false"`（或 `dataExtractionRules` 排除 token）；④敏感 token 不写日志/不写崩溃上报 |
| **APP-04** | **高** | 聊天 WebSocket | `lib/core/config/server_config.dart:33-40`（`chatWs`: `ws://host:port/ws/chat?token=...`）；`lib/core/network/chat_socket.dart:49-54`（`WebSocket.connect(config.chatWs(token).toString())`） | token 放在 URL query：服务端访问日志、网关/代理日志、WKWebView/系统日志、抓包工具都会记录完整 URL；配合明文 ws（APP-02）可被同网直接嗅探，导致账号接管 | 静态确认（未动态抓包） | ①WS 改 WSS；②token 放 `Authorization`/`Sec-WebSocket-Protocol` 或先用一次性 ticket 换连接（握手时带 ticket，URL 不含 token）；③服务端日志脱敏 URL query；④缩短 WS 使用的短期凭证 |
| **APP-05** | 中 | 聊天本地镜像 | `lib/core/storage/chat_database.dart:14-22`（`ershou_chat.db`，`openDatabase` 明文、无 SQLCipher）；`lib/core/storage/chat_dao.dart:23`（消息 `content` 原样入库）、`:38`（原样读出）；`core/storage/chat_cache.dart` 同库存会话/消息 | 聊天记录、图片 URL、对方昵称等明文存储在应用私有目录；root/备份/取证可读；多账号切换后历史 scope 数据可能残留（`chat_dao` 以 scope 隔离，但未加密） | 静态确认 | ①用 SQLCipher 加密数据库（或至少加密消息 body）；②退出登录时按 scope 清理（已有部分逻辑，确认全量）；③敏感附件 URL 不落库或过期化 |
| **APP-06** | 中 | 地图 WebView | `lib/ui/pages/map_page.dart:180-201`（`JavaScriptMode.unrestricted` + `addJavaScriptChannel('FlutterBridge')` + `loadRequest` 加载 `repo.amapPageUrl`，无 URL 白名单/`onNavigationRequest` 限制）、`:256-301`（桥消息 handler）、`:651-659`（`_openAmapNavigation` 把同一 WebView 导航到 `https://uri.amap.com/...`）；页面 URL 由 `lib/data/api_repository2.dart:226-241` 用 `http` base 拼接 | WebView 加载的是后端下发的 HTTP 页面；若服务器被伪造/攻破（APP-01/02），页面内容可控；且导航无白名单，外部页面（如 uri.amap.com）可与 `FlutterBridge` 通信（当前桥只处理 ready/select，能力有限，但可造成地图 UI 欺骗/异常数据注入） | 静态确认（未动态注入） | ①WebView 只加载 `https` 且域名/路径白名单（`onNavigationRequest` 拦截其他 URL）；②JS 通道仅在受信页面启用（按 URL 判断）或用 `postMessage` 限定 origin；③桥接消息做 schema/来源校验；④外部导航改用系统浏览器（`url_launcher`）而非同 WebView |
| **APP-07** | 中 | Android 构建 | `android/app/build.gradle.kts:28-34`（`release { signingConfig = signingConfigs.getByName("debug") }`） | Release 包用 debug 签名：可被同包名替换/二次打包分发，升级校验/商店分发不可信；仅演示项目可接受 | 静态确认 | 生成正式 keystore，release 用正式签名；CI 保管密钥；包名/版本升级策略 |
| **APP-08** | 中 | 发布/编辑表单 | `lib/ui/pages/publish_page.dart:153-166`（`_validate`：价格>0；原价仅在 `>0` 时比较）、`:182-186`（`stock = int.tryParse(...) ?? 1`）、`:633-641`（库存 `digitsOnly`，**0 可提交**、无上限）；价格输入 `:563-567`/`:581-590` 仅过滤 `[0-9.]`，无上限；提交走 `lib/data/api_repository2.dart:650`（原样发字段） | 客户端校验可绕过（直接调 API 即无保护），且 UI 本身允许库存=0、价格/原价无上限；若后端未做同等校验，可造成 0 库存商品、异常价格、库存溢出等脏数据 | 静态确认（未实测接口绕过） | ①后端强制校验（价格区间/库存≥1/原价≥售价/标题长度）；②前端补库存>0、上限、价格上限校验并 clamp；③发布接口加防重放/幂等（可选） |
| **APP-09** | 中 | 扫码连接 | `lib/ui/pages/connect_page.dart:188-208`（`_parseQr` 接受任意 host/port，剥掉 scheme 后直接 `ServerConfig(host:...)`）、`:172-185`（扫码后直接 `_connectManual` 探测） | 恶意二维码（打印/聊天/商品图）可把 App 指向攻击者服务器；结合 APP-01/02 可钓鱼登录、窃取验证码/token。当前连接页对 host 无任何白名单/格式限制 | 静态确认 | ①二维码仅接受签名内容（或公钥校验）；②仅允许 HTTPS/受信域名或已保存历史地址；③连接前展示 host/IP+证书指纹并二次确认 |
| **APP-10** | 低 | 登录页 | `lib/ui/pages/login_page.dart:245-247`（release 也显示"开发环境短信是 mock 的：任意合法手机号 + 123456 即可登录"+"当前服务器：xxx"） | 若后端 mock 验证码在非开发环境未关闭，任何人都能登录任意手机号（客户端已明示该规则）；即使关闭，也泄露开发配置/当前服务器地址（社工辅助） | 静态确认（未实测登录绕过） | ①用 `kDebugMode`/构建 flavor 包裹提示，release 不显示；②后端 mock 只允许 dev profile；③提示中不显示服务器地址 |
| **APP-11** | 低 | 敏感页面（聊天/我的面交/我的） | 全仓无 `FLAG_SECURE`；`core/app_lifecycle.dart` 仅在回前台刷新，无截图/后台快照遮挡；页面 `chat_page.dart`/`meetup_orders_page.dart`/`profile_page.dart` | 聊天记录、手机号（脱敏）、面交地点/时间可被系统截图/最近任务快照/录屏采集并外泄 | 静态确认（grep 无 FLAG_SECURE） | ①聊天/订单/个人信息页开启 `FLAG_SECURE`（或页面退出时 `clearFlags`）；②后台切换时遮挡（Android `onPause`/iOS snapshot 遮罩）；③按需脱敏显示 |
| **APP-12** | 低 | 地图页日志 | `lib/ui/pages/map_page.dart:265`（`debugPrint('[map] bridge type=$type raw=${message.message}')`，无 `kDebugMode` 包裹；`:371` 的 selftest 有 kDebugMode 守卫，但日志没有） | Release 包仍向 logcat 输出桥接事件原文，可能包含点位名称/坐标/页面事件；设备被接入调试或日志被采集时泄露位置信息 | 静态确认（`debugPrint` 在 release 不自动移除） | 用 `if (kDebugMode)` 包裹或改 `debugPrintSynchronously` 受控；release 关闭地图调试日志 |
| **APP-13** | 低 | 图片上传 | `lib/ui/pages/chat_page.dart:462-468`（`ImagePicker().pickImage`，quality 85/maxWidth 1600，无文件大小/类型二次校验）；`lib/data/api_repository2.dart:623-630`（`MultipartFile.fromFile(filePath)` 直接上传任意本地路径）；发布页 `publish_page.dart:116-130` 类似 | 恶意/被篡改设备可上传任意文件（类型/大小依赖后端校验）；超大文件可造成流量/存储消耗；图片 EXIF 可能带位置（客户端未剥离） | 静态确认（未实测） | ①客户端校验 mime/尺寸/大小并压缩/剥离 EXIF；②后端做文件头校验、大小限制、存储隔离；③上传前重编码图片 |
| **APP-14** | 低 | 本地用户快照 | `lib/core/storage/local_store.dart:29-37`（`ershou.user` 明文 JSON）、`lib/data/models.dart:285-300`（`toJson`/`encode` 含 `phone`/`roleFlags`/`isAdmin`）；`profile_page.dart:168-179` 用 `isAdmin` 显示"运营"标识 | 本地快照可被 root/备份篡改（如改 `isAdmin`/`roleFlags`），仅影响本地 UI 展示与入口（后端接口鉴权应兜底）；但会误导用户/测试人员以为有权限 | 静态确认 | ①本地不存角色/权限等安全敏感字段（只存展示字段）；②UI 权限仅作展示，任何管理入口由后端鉴权兜底；③如必须存，加完整性校验（签名/HMAC） |
| **APP-15** | 低 | 服务器扫描 | `lib/core/discovery/lan_scanner.dart:130-158`（网段扫描按 /24 全量 254 主机批量探测）、`:261-288`（候选排序含全量网段×254）、`:213-224`（同时向广播/各网段 .255 发探测包） | 扫描会对内网大量主机发起连接（网络噪音、被 IDS/防火墙记录），也可能被攻击者利用进行内网测绘（App 被动充当扫描器）；无速率限制/用户提示 | 静态确认 | ①限制扫描范围/速率（并发+超时已有，可再加用户确认与提示）；②优先 USB/保存地址/二维码；③企业/校园网默认禁用全量扫描 |
| **APP-16** | 低 | 图片加载 | `lib/core/config/server_config.dart:46-64`（`resolveAssetUrl` 只重写本机/后端 host，其他绝对 URL 原样返回）；`lib/ui/widgets/net_image.dart:28`（按 URL 加载） | 若后端被攻破/被伪造（APP-01/02），服务端可返回任意外部图片 URL，App 会向第三方发起请求，泄露客户端 IP/UA 等元数据（图片内容可被用于追踪/钓鱼展示） | 静态确认 | ①图片 URL 只允许当前服务器 origin/受信 CDN 白名单；②外部 URL 拒绝或代理下载；③图片域名与 API 域名绑定 |
| **APP-17** | 信息 | iOS | 仓库 `ershou_app/` 下无 `ios/` 目录（仅 `android/`、`lib/`、`build/`、`test/` 等） | 本轮 iOS 侧（`Info.plist`/ATS/URL Scheme/Universal Link）不适用；若后续补 iOS：需审计 ATS（默认禁止明文）、`CFBundleURLTypes`、`LSApplicationQueriesSchemes`、剪贴板/相册权限文案 | 不适用（无 iOS 工程） | 后续 iOS 版本按同样标准审计；ATS 默认 `NSAllowsArbitraryLoads=false` |
| **APP-18** | 信息（正向） | Android 组件/客户端实践 | `android/app/src/main/AndroidManifest.xml:19-45`（仅一个 `MainActivity` 且 `exported=true` 仅用于 LAUNCHER；无 deep link/`VIEW` data、无 `provider`/`service`/`receiver`）；`:52-57`（`queries` 仅 `PROCESS_TEXT`）；全仓 grep 无 `Clipboard`/`url_launcher`/`FileProvider`/硬编码 key；`api_client.dart:126-128`（10002 清本地会话）；`chat_dao.dart:179-184`（撤回时本地删原文）；`profile_page.dart:68-69`（图片缓存清理入口）；`pubspec.yaml` 无崩溃上报 SDK | 说明已做对的最小化措施：导出面小、无深链攻击面、无剪切板泄露、无硬编码密钥、会话失效清理、撤回本地删除、图片缓存可清理、无第三方崩溃上报（减少 PII 外发） | 静态确认 | 保持；后续新增组件/深链/上报 SDK 时按本报告标准评审 |

---

## 4. Top 3（优先处理）

### Top1 · APP-01：局域网"一键连接"可被伪造服务器劫持（高）

**攻击路径（静态推导）**：
1. 攻击者在同一 WiFi 广播应答 `{app:"ershou-server", port:8080}`（`lan_scanner.dart:162-211` 只校验该字段，host 取自应答源 IP）；
2. App 在冷启动/无保存地址时自动扫描（`connect_page.dart:49-119`），把攻击者列为"广播发现"候选；
3. 用户点连接（或探测"上次地址"时恰好指向攻击者）→ `_use` 保存该服务器（`connect_page.dart:157-170`）；
4. App 以明文 HTTP 向该服务器发 `/auth/sms-code`、`/auth/login`（手机号+验证码）、后续所有 Bearer token 请求；攻击者直接拿到可长期冒用的 token（10 年有效）。

**影响**：账号接管、验证码/手机号泄露、聊天/订单数据泄露；公共 WiFi/校园网场景最危险。扫码（APP-09）与手动输入同样可指向攻击者，且探测只认 `code==0`（`server_config_service.dart:92-99`），伪造成本极低。

**修复**：服务端对发现应答做签名（App 内置公钥验签）；候选仅允许 HTTPS 且校验证书；广播结果必须用户显式确认（禁止静默使用）；连接前展示服务器身份指纹并维护信任列表；默认只走 USB/手动。

### Top2 · APP-02 + APP-04：全链路明文 + token 走 URL（高）

**攻击路径**：同一网络嗅探 HTTP/WS 流量 → 直接读取 `Authorization: Bearer <token>`、聊天消息、图片；WS 的 `?token=` 还会进入服务端访问日志/网关/代理日志（`server_config.dart:33-40`、`chat_socket.dart:49-54`），即使后续升级 TLS，日志里的 token 仍会泄露。

**影响**：token 窃取后可长期冒用（10 年）；聊天内容/交易信息泄露；响应可被篡改（钓鱼登录/订单）。

**修复**：全量 HTTPS/WSS（`usesCleartextTraffic=false` + `network_security_config` 仅调试白名单）；证书校验/固定；WS token 移出 URL（Header/子协议/一次性 ticket）；服务端日志脱敏；缩短 token TTL + refresh + 可撤销。

### Top3 · APP-03：10 年有效 token 明文落盘（高）

**攻击路径**：`SharedPreferences` 明文存 `ershou.token`（`local_store.dart:19-27`）；Android 默认 `allowBackup=true`（manifest 未关闭），设备备份/云端备份、root、取证工具可直接读取；服务端 token 有效期 10 年且 `active-timeout=-1` 永不失效 → 一次泄露长期可用。

**修复**：Android Keystore/`flutter_secure_storage` 加密存储；`allowBackup=false`/备份排除；服务端短 TTL + 刷新/撤销 + 设备指纹绑定；登录/敏感操作二次校验（可选生物识别）。

---

## 5. 已做对的安全实践（正向）

- Android 导出面最小：除 LAUNCHER 的 `MainActivity` 外无 `exported=true` 组件、无 `provider`/`service`/`receiver`、无 deep link/`VIEW` data，攻击面小（APP-18）。
- 无 `Clipboard` 调用、无 `url_launcher` 外链打开、无 FileProvider 分享路径；`queries` 仅 `PROCESS_TEXT`。
- 全仓无硬编码 API key/secret/password（高德 key 在后端下发 HTML 中，App 侧无密钥）。
- `10002` 未登录时自动清本地会话并回登录（`api_client.dart:126-128`、`providers.dart`）。
- 聊天撤回时本地原文删除（`chat_dao.dart:179-184`）；图片缓存提供手动清理入口（`profile_page.dart:68-69`）。
- 未引入第三方崩溃上报/埋点 SDK，减少 PII 外发面。
- 定位使用原生 `geolocator` 且按需请求权限；相册选图使用系统 SAF，未申请全量存储权限。

---

## 6. 未验证项与动态验证建议（本轮未执行，供 QA/后续）

> 受"只读、不发攻击请求"纪律限制，以下均未执行；给出可在**授权环境**中验证的方法。

| # | 待验证 | 建议方法 | 预期结论 |
| --- | --- | --- | --- |
| 1 | APP-01 伪造服务器 | 授权测试 WiFi 中起一个假 UDP 应答 + 假 HTTP 服务（同网段），观察 App 自动扫描是否出现候选、连接后请求是否被捕获 | 确认可达则升级为"已验证"高危 |
| 2 | APP-02 明文嗅探 | PC 开热点/MITM 代理，手机接入后抓包看 Authorization/WS URL/聊天内容 | 确认明文可读 |
| 3 | APP-03 备份提取 | `adb backup -f app.ab com.ershou.ershou_app` 后解析 shared_prefs（debug 包）；或 root 设备读 `/data/data/.../shared_prefs` | 确认 token 明文可提取 |
| 4 | APP-06 WebView 外部页 | 拦截/替换 amap-html 响应注入 `FlutterBridge.postMessage`，观察桥消息处理 | 确认外部内容可触达桥（当前能力有限） |
| 5 | APP-10 mock 登录 | 用任意手机号+123456 对网关/单体登录（仅在授权测试环境） | 确认后端是否关闭 mock |
| 6 | APP-08 校验绕过 | 直接调用发布接口传 stock=0/负价（抓包重放），观察后端是否拒绝 | 判断是否需要后端补校验 |
| 7 | APP-13 上传 | 选取超大/非图片文件上传，观察后端校验 | 确认服务端兜底 |

---

## 7. 修复优先级建议

| 优先级 | 条目 | 归属 | 说明 |
| --- | --- | --- | --- |
| **P0（立即）** | APP-01/02/03/04 | 客户端 + 后端/运维 | 威胁模型是"同网攻击者/设备丢失"，与真实用户环境相关；演示前至少关闭广播自动发现、强制 USB/手动 + 提示明文风险 |
| P1 | APP-05/06/07/08/09 | 客户端 + 后端 | 聊天本地加密、WebView 白名单、正式签名、后端校验、扫码白名单 |
| P2 | APP-10/11/12/13/14/15/16 | 客户端 | 提示/日志/截图保护/上传校验/本地字段/扫描范围/图片 URL 白名单 |
| 信息 | APP-17/18 | — | iOS 未实现；正向实践保持 |

> 演示环境（当前 USB 隧道 + `127.0.0.1:8080`）下，局域网伪造/嗅探风险因回环通道而降低，但**明文 HTTP 与 token 落盘问题仍然存在**；若切换到 WiFi 直连演示，上述高危项会立即暴露。

---

## 8. 附录

### 8.1 证据文件索引（相对 `ershou_app/`）

| 文件 | 用途 |
| --- | --- |
| `lib/core/storage/local_store.dart` | token/user/缓存明文存储（APP-03/14） |
| `lib/core/config/server_config.dart` | http/ws 固定、URL 重写（APP-02/04/16） |
| `lib/core/config/server_config_service.dart` | 服务器探测信任逻辑（APP-01） |
| `lib/core/discovery/lan_scanner.dart` | UDP 广播/网段扫描/候选生成（APP-01/15） |
| `lib/core/network/api_client.dart` | Dio 配置、Bearer 注入、10002 清理（APP-02/18） |
| `lib/core/network/chat_socket.dart` | WS token URL（APP-04） |
| `lib/core/storage/chat_database.dart` / `chat_dao.dart` | 聊天明文 SQLite（APP-05/18） |
| `lib/ui/pages/connect_page.dart` | 自动扫描/扫码/手动连接（APP-01/09） |
| `lib/ui/pages/map_page.dart` | WebView/JS 桥/调试日志（APP-06/12） |
| `lib/ui/pages/publish_page.dart` | 发布表单校验（APP-08） |
| `lib/ui/pages/login_page.dart` | mock 登录提示（APP-10） |
| `lib/ui/pages/chat_page.dart` / `publish_page.dart` | 图片选择/上传（APP-13） |
| `lib/data/api_repository2.dart` / `platform_repository.dart` | 接口路径/上传（附录接口表） |
| `android/app/src/main/AndroidManifest.xml` | 明文流量/组件/权限/备份（APP-02/03/18） |
| `android/app/build.gradle.kts` | release debug 签名（APP-07） |
| `pubspec.yaml` | 依赖面（无安全存储/崩溃上报） |

### 8.2 接口—页面对照（仓库方法级，便于复核）

- 认证/用户：`api_repository.dart:11-44`（`/auth/sms-code`、`/auth/login`、`/users/me`、`/auth/logout`）；`api_repository2.dart:508-526`（`/users/{id}/profile`、block/unblock、`/schools/{id}/campuses`）。
- 商品：`api_repository.dart:92-125`（`/items`、`/items/{id}`、`/categories`）；`api_repository2.dart:565-650`（`/items/mine`、`/items/{id}` PUT/offline/relist、`/items` POST、`/files/upload`）。
- 收藏/购物车：`api_repository2.dart:544-560`（favorites）、`:447-492`（`/cart`、PATCH/DELETE、`/cart/checkout`）。
- 聊天：`api_repository2.dart:15-122`（sessions/messages/send/recall/read/delete/batch-delete/unread/quick-replies）。
- 地图/点位：`api_repository2.dart:153-305`（`/map/points`、`/trade-points`、`/trade-points/nearby`、`/map/regeo`、`/map/amap-status`、`/trade-points` POST、`/trade-points/search-hit`、`/map/amap-html`）。
- 面交单：`api_repository2.dart:333-429`（`/meetup-orders` GET/POST、accept/reject/confirm/point、todo-count、active）。
- 平台严选：`platform_repository.dart:20-118`（`/platform/shop/*`、`/platform/me/coupons/{id}/claim`、`/platform/me/flash-sale/{itemId}/grab`、`/platform/me/items/{id}/buy`）。

### 8.3 审计命令（可复核，只读）

```powershell
# 在 E:\ershou\ershou_app 下
Get-ChildItem lib\ui\pages -Filter *.dart | Select-Object Name
Get-Content lib\core\storage\local_store.dart | Select-String 'token|serve' 
Get-Content lib\core\config\server_config.dart | Select-String 'http|ws|origin'
Get-Content lib\core\discovery\lan_scanner.dart | Select-String 'broadcast|ershou-server|dg.address'
Get-Content android\app\src\main\AndroidManifest.xml | Select-String 'usesCleartextTraffic|exported|provider'
Get-Content android\app\build.gradle.kts | Select-String 'signingConfig'
```

---

> 报告完。所有"高"项均为静态可复核结论；未进行任何动态攻击验证（遵守只读纪律）。建议 Lead 安排后端/安全同学在授权环境按 §6 做动态复验，并在演示前至少落实 APP-01/02/03/04 的缓解措施。
