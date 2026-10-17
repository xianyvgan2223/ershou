# 移动端基建 · app-member 记录

| 项 | 值 |
| --- | --- |
| 成员 | `app-member` |
| 负责功能 | Flutter 移动端基建：局域网发现（UDP 48899 / 网段扫描 / USB 隧道）、服务器配置持久化、API 客户端、本地存储与图片缓存、首页按需同步（`refreshIfStale`）、生命周期、连接页 |
| 记录文件 | `智能体团队/07-app-移动端基建.md` |
| 本轮状态 | `已完成` |
| 更新时间 | `2026-10-14` |
| 覆盖功能编号 | 功能清单 #16、#27（移动端基建部分） |

## 1. 本轮状态

- 状态：`已完成`；负责人：`app-member`；更新时间：`2026-10-14`。
- 覆盖功能编号：`功能清单.md:28`（#16 移动端 App）、`功能清单.md:44`（#27 首页数据按需同步）、`功能清单.md:307-322`（#20 小节内的移动端基建行）、`功能清单.md:383-390`（#27 明细）。
- 本轮是**只读核实轮**：只读了 `ershou_app/lib/**`、`ershou_app/test/**`、`ershou_app/pubspec.yaml`、`ershou_app/android/app/src/main/AndroidManifest.xml`、`ershou-server` 的 `LanDiscoveryResponder` / `application*.yml` / `pom.xml` / `GlobalExceptionHandler` / `ResultCode`、`scripts/{lan-info,mobile-connect,mobile-install}.ps1`、根 `README.md`、`ershou_app/README.md`、`功能清单.md`。
- **未执行**：`flutter build` / `flutter analyze` / `dart pub get` / `flutter test`（避免写 `build`、`.dart_tool` 并刷屏）；**未启动**后端/前端；**未运行** `scripts/mobile-*.ps1`；**未真机、未抓包**。
- 除本文件外未修改任何源码/配置/文档/脚本。所有 `路径:行号` 来自本轮真实读到的内容；运行期未验证的部分集中写在第 6 节。

## 2. 功能边界

- 做什么：让手机"找得到、连得上、连得上之后不用反复登录" —— ① 服务器发现（UDP 48899 广播应答 / 本机网卡 /24 网段扫描 / adb reverse USB 隧道 / 手动 `IP:8080` / 扫二维码）与结果校验；② 地址与历史地址持久化、冷启动复用；③ API 客户端（统一响应体拆包、`code != 0` 抛异常、`Authorization: Bearer` 注入、超时与错误文案）；④ 本地键值存储（`ershou.token` 等）与图片磁盘缓存（800 个 / 60 天 + 清理入口）；⑤ 首页 `refreshIfStale`（25 秒新鲜期，切回首页 Tab / App 回前台触发）；⑥ App 生命周期钩子（回前台重连 WS、校准未读、补拉首页）；⑦ 连接页 UI 与提示文案。
- 不做什么：登录/验证码/学校校区的业务实现（#1 `auth-member`）；商品、购物车、面交、收藏（#2/#4）；校内地图与点位合并（#3/#25/#26）；聊天协议与本地消息镜像（#5）；运营后台（#6）；后端 UDP 应答本身的实现细节只核实不修改（`LanDiscoveryResponder` 归后端组）。

## 3. 代码地图

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 入口 | `ershou_app/lib/main.dart:13-35` | `SharedPreferences.getInstance()` → `ProviderScope` override `sharedPreferencesProvider` / `chatCacheProvider` |
| 入口 | `ershou_app/lib/app.dart:18-45` | 首屏三选一：连接页（无地址）/ 登录页（有地址未登录）/ Shell（已登录）；`ChatConnectionKeeper` 全包 |
| 发现 | `ershou_app/lib/core/discovery/lan_scanner.dart:9` | `enum DiscoveryMethod { saved, broadcast, usbTunnel, sweep, manual }` |
| 发现 | `lan_scanner.dart:62-63` | `defaultPort = ServerConfig.defaultPort`(8080)、`broadcastPort = 48899` |
| 发现 | `lan_scanner.dart:68-78` | `trySaved()`：探测上次地址，1.5s 超时 |
| 发现 | `lan_scanner.dart:81-91` | `tryManual(host, port)`：手动/扫码共用的校验入口 |
| 发现 | `lan_scanner.dart:93-118` | `discover()` 真实顺序：UDP 广播 → `127.0.0.1`（USB 隧道）→ 网段扫描 |
| 发现 | `lan_scanner.dart:130-158` | 网段扫描：64 个一批并发、单点 700ms 超时 |
| 发现 | `lan_scanner.dart:162-211` | `_broadcast()`：`RawDatagramSocket.bind(anyIPv4,0)` + 广播，1200ms 内取首个 `app == 'ershou-server'` 应答 |
| 发现 | `lan_scanner.dart:213-224` | 广播目标：`255.255.255.255` + 每张网卡的 `x.y.z.255` |
| 发现 | `lan_scanner.dart:227-243` | 网段前缀：所有非回环 IPv4 的 /24 + 兜底 `192.168.1/0/31`、`10.0.0` |
| 发现 | `lan_scanner.dart:261-288` | 候选排序：常用网段优先、23 个常见主机位优先、再补全 1..254，末尾 `127.0.0.1` |
| 配置 | `ershou_app/lib/core/config/server_config.dart:15/27-31/34-40` | `defaultPort=8080`、`origin`/`apiBase`(`/api/v1`)、`chatWs(token)`(`?token=`) |
| 配置 | `server_config.dart:42-64` | `resolveAssetUrl()`：MinIO 绝对地址只换主机名、**保留端口**（9010） |
| 配置 | `ershou_app/lib/core/config/server_config_service.dart:28-29` | 持久化 key：`ershou.server.config` / `ershou.server.history` |
| 配置 | `server_config_service.dart:40-49` | `save()`：写当前地址 + 历史（最多 6 条） |
| 配置 | `server_config_service.dart:58-111` | `probe()`：`GET {apiBase}/categories`，要求 HTTP 200 且 `code == 0` |
| 配置 | `server_config_service.dart:113-127` | `humanize()`：超时/连不上/网络错误的可读文案 |
| 连接页 | `ershou_app/lib/ui/pages/connect_page.dart:36-39,50-78` | `initState` → `_trySaved()`：先探测上次地址，失败则自动接一次扫描 |
| 连接页 | `connect_page.dart:80-120` | `_startScan()` 订阅 `discover()` 事件流 |
| 连接页 | `connect_page.dart:131-155` | 手动输入 `IP:8080` → `tryManual` → `_use` |
| 连接页 | `connect_page.dart:157-170` | `_use()`：`activeServerProvider.use()` 落盘并切地址；silent 时不弹提示 |
| 连接页 | `connect_page.dart:172-208,550-621` | 扫码：`mobile_scanner` 弹窗 + `_parseQr`（支持 `IP:port`、`http://IP:port`、`{"host","port"}`） |
| 连接页 | `connect_page.dart:504-528,530-547` | 历史地址列表、连不上排查提示（提示里只给 `adb reverse tcp:8080`） |
| 状态 | `ershou_app/lib/core/providers.dart:21-27,45-51` | `sharedPreferencesProvider`、`localStoreProvider`、`serverConfigServiceProvider`、`lanScannerProvider` |
| 状态 | `providers.dart:53-63` | `apiClientProvider`：`configure(活动地址)` + `setToken(本地 token)` + `onUnauthorized` |
| 状态 | `providers.dart:326-342` | `activeServerProvider`：`build()` 从 prefs 载入；`use()` 落盘；`forget()` 无调用点 |
| 状态 | `providers.dart:344-466` | `AuthState`/`AuthNotifier`：`isLoggedIn`、`login`、`refreshMe`、`sessionExpired`、`logout` |
| 状态 | `providers.dart:524-704` | `ItemFeedNotifier`：首屏缓存、`_lastLoadedAt`、`_staleAfter=25s`、`refreshIfStale`、`refresh`、`loadMore` |
| 状态 | `providers.dart:706-709` | `nowProvider`：可注入时钟（测试用假时钟验证新鲜期） |
| 存储 | `ershou_app/lib/core/storage/local_store.dart:12-27,121-125` | key `ershou.token` / `ershou.user` / `ershou.userId`；`clearSession()` |
| 存储 | `local_store.dart:63-93,95-119` | `ershou.cache.` 带时间戳 JSON 缓存、`ershou.ui.` 界面偏好 |
| 存储 | `ershou_app/lib/core/storage/image_cache.dart:8-24` | `ErshouImageCache`：key `ershouImages`、60 天、800 个、`clear()` |
| 图片 | `ershou_app/lib/ui/widgets/net_image.dart:26-72` | `NetImage`：地址重写 + `memCacheWidth`（≤1600）+ 占位/错误兜底 |
| 图片 | `net_image.dart:75-113` | `Avatar`：地址重写 + 首字兜底 |
| 图片 | `ershou_app/lib/data/api_repository.dart:134-138` | `ItemRepository.imageUrl()` → `ServerConfig.resolveAssetUrl` |
| 网络 | `ershou_app/lib/core/network/api_client.dart:59-86` | Dio：6s 连接 / 12s 接收 / 20s 发送；`Authorization: Bearer` 拦截器 |
| 网络 | `api_client.dart:100-137,160-179` | `envelope()` 拆包、`code != 0` → `ApiException`、`10002` → `onUnauthorized`；Dio 错误人话化 |
| 生命周期 | `ershou_app/lib/core/app_lifecycle.dart:20-51` | `ChatConnectionKeeper`：resumed → `ensureConnected` + 未读刷新 + `bump` + `refreshIfStale` |
| Shell | `ershou_app/lib/ui/pages/shell_page.dart:12-14,31-43,52-84` | IndexedStack 保活 4 个 Tab；发布返回后 `invalidate()` + `refreshIfStale()` |
| 首页 | `ershou_app/lib/ui/pages/home_page.dart:30-43,52-59` | `initState` 首拉 + `ref.listen(navIndexProvider)` 切回首页时 `refreshIfStale()` |
| 我的 | `ershou_app/lib/ui/pages/profile_page.dart:64-72,94-108` | 「服务器地址」入口 → ConnectPage；「清理图片缓存」→ `ErshouImageCache.clear()` |
| 测试 | `ershou_app/test/item_feed_stale_refresh_test.dart:87-148` | 6 项：拉取/新鲜期跳过/过期补拉/冷启动空时间戳/invalidate 强拉/loading 不并发 |
| 测试 | `ershou_app/test/lan_probe_test.dart:54-117` | 6 项集成：探测后端、局域网 IP、错误地址、`tryManual`、拆包、图片端口不被改写（连不上 127.0.0.1 自动 skip） |
| 脚本 | `scripts/mobile-connect.ps1:51-53,61-87` | 三条隧道 8080/9010/9011 + 手机侧实测 API 与真实图片对象 |
| 脚本 | `scripts/lan-info.ps1:11-36` | 打印手机该连的 IP 并自测 `/api/v1/categories` |
| 脚本 | `scripts/mobile-install.ps1:79-111` | `adb install -r` 保留数据；装前/装后对比 `ershou.token` |
| 后端 | `ershou-server/.../common/discovery/LanDiscoveryResponder.java:44-101` | UDP 48899 应答 `{app:"ershou-server", port, name, ips}` |
| 后端 | `ershou-server/src/main/resources/application.yml:33-54` | Sa-Token：`token-name: Authorization`、`token-prefix: Bearer`、`timeout: 315360000`、`active-timeout: -1` |
| 后端 | `ershou-server/pom.xml:77-83` | `sa-token-spring-boot3-starter` + `sa-token-redis-jackson`（会话落 Redis） |
| 后端 | `application-dev.yml:11-22` | `spring.data.redis`（127.0.0.1:6380） |
| 后端 | `common/exception/GlobalExceptionHandler.java:24-27` + `common/result/ResultCode.java:9` | `NotLoginException` → `code 10002` |

## 4. 已核实结论

| # | 结论 | 证据 |
| --- | --- | --- |
| C1 | 一键扫描**三条路径全部真实实现**：UDP 广播（后端 48899 应答）→ USB 隧道（探测手机自己的 `127.0.0.1:8080`）→ 网段扫描（/24 并发 64、单点 700ms），且只有"真的是本项目后端"才被采纳 | `lan_scanner.dart:93-118`（顺序）、`lan_scanner.dart:162-211`（广播）、`lan_scanner.dart:106-118`（隧道）、`lan_scanner.dart:130-158`（扫描）；后端 `LanDiscoveryResponder.java:68-101` 回 `{app:"ershou-server",port,name,ips}` |
| C2 | 手动输入 `IP:8080` 与扫二维码**都是真实现**：手动走 `tryManual` 校验后才保存地址；二维码支持 `10.34.29.94:8080`、`http://10.34.29.94:8080`、`{"host":...,"port":...}` 三种内容，扫完复用同一条手动校验链路 | `connect_page.dart:131-155`；`connect_page.dart:172-186`、`188-208`（`_parseQr` 三个分支）、`550-621`（`MobileScanner` + 相机不可用兜底） |
| C3 | **"冷启动先探测上次地址"只在连接页被渲染时成立**，不是真正的冷启动行为。有已存地址时首屏直接是登录页/Shell（`app.dart:41`，`server == null` 才显示 ConnectPage），`_trySaved()` 只在 `ConnectPage.initState` 里跑 | `app.dart:39-43`、`providers.dart:329-331`（`build()` 从 prefs 载入地址）、`connect_page.dart:36-39`、`connect_page.dart:50-78`；无地址时 400ms 后自动 `_startScan()`（`connect_page.dart:54-57`） |
| C4 | 登录态链路的**①本地落盘**成立：token 写 `SharedPreferences` 的 `ershou.token` | `local_store.dart:12`、`local_store.dart:19-27`；写入调用点 `providers.dart:396-404`（登录成功） |
| C5 | 登录态链路的**②冷启动恢复**成立：`AuthNotifier.build()` 直接读回 token 与用户快照（不阻塞首次渲染），`apiClientProvider` 建 client 时把同一个 token 装上 | `providers.dart:373-378`、`providers.dart:53-56`、`providers.dart:353`（`isLoggedIn = token 非空`） |
| C6 | 登录态链路的**③服务端会话落 Redis** 成立：`sa-token-redis-jackson` + `spring.data.redis` 已配置，`timeout: 315360000`（10 年）、`active-timeout: -1`（关闭活跃超时） | `pom.xml:77-83`、`application-dev.yml:11-22`、`application.yml:45`、`application.yml:48`；README.md:112-120 的描述与代码一致 |
| C7 | **"网络失败被吞掉"的老坑确实已修**：三态 `AmapKeyState` 在 `api_repository2.dart` 定义，`amapKeyState()` 只在后端明确答复 `configured != true` 时返回 `missingKey`，任何异常走 `unreachable`；`map_page.dart` 按三态给不同提示 | `api_repository2.dart:129-140`、`api_repository2.dart:256-266`、`map_page.dart:122-135`、`map_page.dart:137-167`；全仓 `return false;` 检索只剩注释里的历史说明（`api_repository2.dart:247`、`map_page.dart:144`），无残留实现 |
| C8 | `refreshIfStale` 的三要素成立：25 秒新鲜期常量、只在 `refresh()` **成功**后记录时间戳、切回首页 Tab 与 App 回前台两个触发点 | `providers.dart:561`（`_staleAfter = 25s`）、`providers.dart:571-576`、`providers.dart:632`（成功才写 `_lastLoadedAt`）、`home_page.dart:57-59`、`app_lifecycle.dart:35-44`；`功能清单.md:388` 与代码一致 |
| C9 | `refreshIfStale` **有防重复请求**：`state.loading` 为真直接返回；`refresh()` 在首个 `await` 之前同步把 `loading` 置真，因此并发调用不会重复发请求 | `providers.dart:571-572`、`providers.dart:609-611`；被测试钉住：`test/item_feed_stale_refresh_test.dart:140-148`（loading 期间只发 1 次）、`:95-106`（新鲜期 0 次）、`:108-119`（过期 1 次） |
| C10 | 图片链路与 `adb reverse tcp:9010` 的对应关系成立：后端图片是 MinIO 绝对地址，`resolveAssetUrl()` 只换主机名、保留 9010，走 USB 隧道时变成 `http://127.0.0.1:9010/...`，所以必须转发 9010 | `server_config.dart:42-64`、`api_repository.dart:134-138`、`net_image.dart:26-33`；`scripts/mobile-connect.ps1:51-53`（8080/9010/9011 一起转）、`mobile-connect.ps1:69-87`（用真实对象校验 9010）；README.md:66-68 |
| C11 | 图片请求**不携带 token**：`NetImage`/`Avatar` 的 `CachedNetworkImage` 没有传 `httpHeaders`，因此图片流量（可能被缓存/CDN）不会带凭证 | `net_image.dart:47-67`、`net_image.dart:101-111`（均无 `httpHeaders`） |
| C12 | `ApiClient` 的超时与错误处理可用：6s 连接 / 12s 接收 / 20s 发送；`validateStatus` 全放行自行判 `code`；Dio 异常统一转 `ApiException(-2)` 并给人话文案（含"手机与电脑是否同一 WiFi"） | `api_client.dart:66-74`、`api_client.dart:100-137`、`api_client.dart:160-179` |
| C13 | 服务端 10002 能闭环回客户端：`NotLoginException` → `Result.fail(NOT_LOGIN)`（HTTP 200 + `code 10002`，非 HTTP 401），客户端只在 `code == 10002` 触发 `onUnauthorized` → 清本地登录态 | `GlobalExceptionHandler.java:24-27`、`ResultCode.java:9`、`api_client.dart:126-128`、`providers.dart:59-61`、`providers.dart:433-455` |
| C14 | token 存在时**换服务器不会清除 token，也不会要求重新登录**：唯一的换地址入口 `ActiveServerNotifier.use()` 只改地址与历史，随后 `apiClientProvider` 重建并把**同一个 token** 装到新 host 的 client 上 | `connect_page.dart:157-158`（唯一 `.use(` 调用点）、`providers.dart:333-336`、`server_config_service.dart:40-49`、`providers.dart:53-56`、`providers.dart:353`；`setToken(null)` 只在 `providers.dart:453`（`_wipeLocalSession`，仅被 `sessionExpired()` `providers.dart:437` 与 `logout()` `providers.dart:463` 调用） |
| C15 | 换地址后**聊天长连接也会带着同一个 token 连到新 host**（不需要用户任何额外操作） | `providers.dart:120-129`（`watch(activeServerProvider)` + token → 新 `ChatSocket` + `connect()`）、`chat_socket.dart:49-54`、`server_config.dart:34-40`（`?token=`） |
| C16 | `ActiveServerNotifier.forget()` 是**死代码**（全仓 0 调用点）→ 连接页没有"清除/忘记当前地址"入口，脏地址只能靠清 App 数据去掉 | `providers.dart:338-341`；全仓检索 `forget` 只命中定义本身（1 处） |

## 5. 发现的问题

### P0（安全）· 任意同网设备可伪造「服务器地址」并让 App 把长期 token 明文外发（主条，已由 Leader 裁决定级）

- **现象**：`一键扫描` 的广播应答在客户端**没有任何来源可信性校验**：谁先回一个 `{"app":"ershou-server",...}`，App 就把**回包源 IP**（可伪造/可自建）当成服务器，把包里自带的 `port` 当端口；随后只要用户点选这条结果，App 就会在**不退出登录**的前提下把本地保存的 Sa-Token 以 `Authorization: Bearer` 明文 HTTP 发给这个新服务器。
- **证据（完整链路，逐行）**：
  1. `lan_scanner.dart:165` `RawDatagramSocket.bind(InternetAddress.anyIPv4, 0)` + `:167` payload + `:171` `socket.send(payload, target, 48899)`；`:184` 只校验 `map['app'] != 'ershou-server'`；`:185-193` `host` 取 `dg.address.address`、`port` 取包内字段、`label` 取包内 `name` —— 来源网段、序号、签名一律不校验（`:177-198`）。
  2. `server_config_service.dart:74-99` 判定"真的是本项目后端"的唯一依据是 `GET {host}/api/v1/categories` 返回 JSON 且 `code == 0`（`:92-99`）—— 任何自建 HTTP 服务都能满足。
  3. `api_client.dart:75-83` 请求拦截器把 `_token` 无条件写成 `Authorization: Bearer`，**不校验目标 host 是否等于登录时的 host**；`providers.dart:53-56` 每次建 client 都用 `localStoreProvider.token` 重新装上。
  4. 登录后路径（高风险，无需重新登录）：`profile_page.dart:65-67` 我的 →「服务器地址」→ `ConnectPage` → 点「发现的服务器」里被伪造的那条（`connect_page.dart:409-412`）→ `_use()`（`connect_page.dart:157-170`）→ `activeServerProvider.use()`（`providers.dart:333-336`，**不碰 auth、不清 token**）→ `Navigator.pop()`（`connect_page.dart:169`）→ `profile_page.dart:86-90` `.then((_) => _refresh())` → `refreshMe()`（`providers.dart:415-418`）→ `AuthRepository.me()`（`api_repository.dart:35-41`）`GET /users/me` **带 token 明文 HTTP 发到伪造主机**。
  5. 第二条外发通道（连点的机会都不给用户）：换地址后 `chatSocketProvider` 重建并 `connect()`（`providers.dart:120-129`、`chat_socket.dart:49-54`），token 出现在 WS URL 的 query 里（`server_config.dart:34-40`）。
  6. "token 不会被清"的关键证据（Leader 要求补齐）：`use()` 是唯一换地址入口（全仓 `.use(` 仅 `connect_page.dart:158`），它只调 `ServerConfigService.save()`（`server_config_service.dart:40-49`，只写 `ershou.server.config` 与 `ershou.server.history` 两个 key）；`apiClientProvider` 随即重建并 **`setToken(localStoreProvider.token)`**（`providers.dart:56`）；`AuthNotifier` 的 `isLoggedIn` 只看 token 非空（**`providers.dart:353` `bool get isLoggedIn => (token ?? '').isNotEmpty;`**）；`setToken(null)` 仅出现在 `providers.dart:453`，其调用者只有 `sessionExpired()`（`:437`）与 `logout()`（`:463`）——换地址路径两者都不会走。**结论：换服务器 ⇒ token 照旧有效并被发给新 host。**
  7. 放大器（同根因，按 Leader 要求独立成条，见下 P1-A/P1-B）：10 年会话 + `active-timeout: -1`（`application.yml:45,48`）+ `usesCleartextTraffic="true"`（`AndroidManifest.xml:15`）。
- **影响**：同一 WiFi/热点下的任意设备（或热点持有者）可让 App 把服务器切到自己，进而拿到长期有效的 Sa-Token → 账号接管（发布商品、私聊、下单/面交、看个人资料），且受害者界面上不会有"登录已过期"之类的提示。
  - **利用前提（必须写清）**：① 攻击者与手机在同一广播域（同一 WiFi/热点，或能收到/伪造该 UDP 广播）；② 用户在"发现的服务器"列表里点选伪造条目（登录后路径），或在连接页手动填入/扫码到攻击者地址（登录前路径）；③ 攻击者需自建一个能在 `/api/v1/categories` 返回 `code:0` 的 HTTP 服务。
  - **登录前路径（低风险，但非零）**：无 token 时 `api_client.dart:78-80` 的 `t != null && t.isNotEmpty` 判空使请求不带 `Authorization`，**凭证不会外发**；但登录请求本身（`api_repository.dart:22-31`：手机号 + 验证码）会提交给伪造服务器 → 手机号被收集、可被钓鱼（开发环境验证码固定 `123456`，见 README.md:80，等于直接可登录该伪造服务）。
  - **验证状态：仅源码推断，未经真机/抓包验证**（本轮只读，未启动任何服务、未构造伪造应答）。
- **建议修法**：① 换服务器时**显式二次确认**并突出显示目标 IP，且当目标 IP 与当前地址不同网段时给出警告；② `ApiClient` 增加"目标 host 绑定"——token 只允许发往"用户确认过的 origin"，或对换地址后的首个请求只发探测类无凭证请求，用户确认后再注入 token；③ 发现协议加一次性配对码（服务端把 `{code,port}` 放 UDP 应答/二维码，App 校验后再保存地址），或至少校验应答源 IP 属于本机网段；④ 敏感接口改 HTTPS 或自签 pinning（见 P1-A）。

### P1（安全放大器）· 明文 HTTP + 明文落盘 + 备份未关闭：token 可被被动获取

- **现象**：`usesCleartextTraffic="true"` 使全部 API、WS、图片走明文 HTTP/WS；token 以明文写进 `SharedPreferences`；工程未声明 `android:allowBackup="false"`。
- **证据**：`ershou_app/android/app/src/main/AndroidManifest.xml:15`（`usesCleartextTraffic="true"`）、`:14-18`（`application` 标签内**没有** `allowBackup`）；合并产物 `ershou_app/build/app/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml:60` 同样只有 `usesCleartextTraffic`（Android 默认 `allowBackup=true`，属默认值推断，未经 `adb backup` 实测）；`local_store.dart:12,19-27`（明文 key）；README.md:110 自己给出的自查命令就是 `adb shell "run-as ... cat .../FlutterSharedPreferences.xml"`，直接读出 token 明文。
- **影响**：同网嗅探者/AP 持有者无需伪造任何应答即可抓到 `Authorization: Bearer`（10 年有效）；开启 USB/云备份或 root 设备可离线读取 token。
- **建议修法**：关键接口走 HTTPS（自签 + pinning 或内网 mTLS），或至少把 token 放 Android Keystore 加密存储（如 `flutter_secure_storage`），并显式 `android:allowBackup="false"`；`networkSecurityConfig` 只对局域网 IP 放开明文。

### P1（安全放大器）· 会话 10 年 + 关闭活跃超时，凭证一旦泄漏等于永久

- **现象**：`timeout: 315360000`、`active-timeout: -1`。
- **证据**：`application.yml:45`、`:48`（含注释说明为什么不用 `-1` 做永不过期）；README.md:114-120 明确写"会话有效期 = 10 年"。
- **影响**：把 P0/P1-A 的"一次性泄漏"放大为"长期接管"；服务端没有设备级撤销 UI（只能删 Redis key 或让用户退出登录）。
- **建议修法**：保留长会话（符合产品目标），但补可撤销性：桌面/服务端提供"当前登录设备列表 + 强制下线"；敏感操作（改资料、发布、下单）加二次校验；token 泄漏后能按设备吊销。

### P1 · 校园网换 IP 后冷启动不会自动探测/自动扫描，且没有"清除地址"入口

- **现象**：README 声称"冷启动会先探测上次用过的地址"，但代码里探测逻辑只活在 `ConnectPage.initState`；有已存地址时首屏根本不是连接页，于是最常见的"换网后 IP 变了"场景既不会自动探测也不会自动扫描，用户只看到首页/资料页报错。`forget()` 无调用点，无法把脏地址清掉，只能清 App 数据。
- **证据**：`app.dart:39-43`（`server == null` 才显示 ConnectPage）、`providers.dart:329-331`（`build()` 从 prefs 载入，非空即有值）、`connect_page.dart:36-39`（探测只在 `initState`）、`connect_page.dart:50-78`（探测失败才自动扫描）、`providers.dart:338-341` + 全仓 `forget` 仅 1 处命中（死代码）；README.md:56-57、README.md:126、`ershou_app/README.md:15` 的表述与上述真实行为不一致。
- **影响**：校园网/热点换网是**最高频**场景（README.md:61-63 记载 2026-10-10 实际踩到），此时用户要手动"我的 → 服务器地址 → 等待自动扫描"，比文档承诺的多 3 步；找不到入口的用户会以为 App 坏了。
- **建议修法**：① 冷启动时若"地址已存但一次轻量探测失败"→ 直接进连接页并自动扫描（不必等用户点）；② 或在 Shell 页顶部给"连不上服务器，点这里重新扫描"横幅；③ 连接页加"清除当前地址"按钮（复活 `forget()`）；④ 同步修 README 表述。

### P1 · 连接页排查提示只教了 `adb reverse tcp:8080`，照做会导致商品图全部加载失败

- **现象**：连接页兜底提示写"电脑上执行 `adb reverse tcp:8080 tcp:8080`，再点一键扫描（会走 USB 隧道）"，但图片在 MinIO 的 **9010**，USB 隧道下图片地址会被重写成 `http://127.0.0.1:9010/...`；只转 8080 时能连上后端、图片全裂。
- **证据**：`connect_page.dart:538-542`（提示只含 8080）；`server_config.dart:52-63`（重写只换主机名、保留 9010）；`api_repository.dart:134-138`、`net_image.dart:47-67`；正确做法在 README.md:66-68 与 `scripts/mobile-connect.ps1:51-53`（三条隧道），但**脚本与 App 内提示不一致**；客户端没有任何 9010 可达性探测（`server_config_service.dart:74` 只探 `/categories`）。
- **影响**：按 App 内提示操作的用户 100% 遇到"能登录、图全是裂图"，很容易被误判为商品数据问题（历史上已经误判过一次：`server_config.dart:52-53` 注释记载端口被改成 8080 导致 `{"code":90001}`）。
- **建议修法**：把提示改成三条隧道（8080/9010/9011）；在 `probe()` 成功后追加一次 9010 轻量探测（HEAD 一张已知图或 GET 桶根），失败时在连接页/首页给出"图片通道未打通"的明确提示。

### P2 · 扫码路径只有"扫描端"，仓库内没有二维码生成端

- **现象**：连接页支持扫二维码，但没有任何脚本/接口/页面生成包含 `host:port` 的二维码；用户必须自找第三方工具生成。
- **证据**：全仓检索 `qrcode|二维码|QRCode` 的命中只有文档、需求文档与 `LanDiscoveryResponder.java:103` 的注释（"方便在日志/二维码里直接展示"），无生成实现；App 侧解析在 `connect_page.dart:188-208`。
- **影响**：README.md:56 把"扫二维码"列为三条连接路径之一，实际这条路的**前半段（产出二维码）缺失**，功能不对称。
- **建议修法**：`scripts/lan-info.ps1` 或后端启动日志输出一个二维码（如 `/api/v1/lan/qrcode` 或脚本内生成 ASCII/PNG 二维码），内容用 App 已支持的 `{"host","port"}` 格式。

### P2 · 文档与代码的扫描顺序不一致（3 处）

- **现象**：根 README 写"一键扫描（UDP 广播 → 网段扫描 → USB 隧道）"，代码真实顺序是"UDP 广播 → USB 隧道 → 网段扫描"；`ershou_app/README.md` 描述一键扫描时**完全没提** USB 隧道这一步；只有 App 内 UI 文案是对的。
- **证据**：README.md:56（顺序错）、`ershou_app/README.md:11`（漏一步）、`connect_page.dart:344-347`（"依次尝试：UDP 广播 → USB 隧道 → 手机所在网段"）、`lan_scanner.dart:93-118`（真实顺序）、README.md:48（这处又说"第二条路就是它"，自相矛盾）。
- **影响**：排查时按错误顺序预期行为（以为隧道排最后），可能提前放弃等待。
- **建议修法**：三处统一为"广播 → USB 隧道 → 网段扫描"。

### P2 · `LanDiscoveryResponder` 回包里的 `ips` 字段客户端完全没用

- **现象**：后端在应答里下发 `ips`（本机所有可用 IPv4，`LanDiscoveryResponder.java:94-101`），客户端只取 `app/port/name` 与回包源地址（`lan_scanner.dart:184-193`），`ips` 被丢弃。
- **证据**：同上两处；`LanDiscoveryResponder.java:103-125`（`localIps()` 的计算只为下发）。
- **影响**：多网卡主机（VMware/WSL/热点+WiFi 同时在线）时，回包源地址可能是"手机连不上的那张网卡"的地址，而更可能可用的 IP 就在 `ips` 里 —— 目前没有任何兜底。
- **建议修法**：客户端拿到应答后用 `ips` 逐个补探（复用 `probe()`），命中即采纳；或在结果列表里把 `ips` 展示出来供手选。

### P2 · 401 不会清登录态（当前后端不触发，属防御性缺口）

- **现象**：`ApiException.isUnauthorized` 把 `401` 也算未授权（`api_client.dart:24`），但触发 `onUnauthorized` 的条件只有 `code == 10002`（`api_client.dart:126-128`）。若请求经由网关/反代/容器返回 **HTTP 401（非本项目响应体）**，客户端会走 `map == null` 分支抛 `ApiException(401)`，但不清本地登录态 → 用户停在"首页报错又退不出去"的状态。
- **证据**：`api_client.dart:24`、`api_client.dart:117-128`、`api_client.dart:139-147`；后端侧当前是 200+10002（`GlobalExceptionHandler.java:24-27`、`ResultCode.java:9`），所以现状不触发。
- **影响**：仅在未来加网关/换部署方式时暴露；属低成本可补的防御性缺口。
- **建议修法**：`envelope()` 在 `statusCode == 401` 时也调用 `onUnauthorized?.call()`。

### P2 · 「从商品详情返回」不在补拉触发点里（注释里列的场景之一未覆盖）

- **现象**：`providers.dart:554-557` 把"从商品详情返回、切到别的 Tab 再切回来都不重新请求"列为要解决的问题，但 `refreshIfStale()` 的调用点只有"切回首页 Tab"与"回前台"两处；从详情页 pop 回首页不会触发补拉（首页在 IndexedStack 内保活）。
- **证据**：`providers.dart:552-557`、`providers.dart:571-576`、`home_page.dart:57-59`、`app_lifecycle.dart:35-44`；全仓 `refreshIfStale` 命中仅 3 处调用点（`app_lifecycle.dart:43`、`home_page.dart:58`、`shell_page.dart:41`）。
- **影响**：停留在首页 >25 秒后进出详情页，列表仍可能是旧数据；不过此时用户通常是自己操作商品，影响小于切 Tab 场景。
- **建议修法**：若判定要覆盖，可在详情页 pop 的 Future 回调里调一次 `refreshIfStale()`（成本低，新鲜期内无请求）；否则把注释里"从商品详情返回"改成"仅切 Tab/回前台覆盖"。

### P2 · `_stopScan()` 只取消订阅，已在飞的探测请求不会中断

- **现象**：批次内 64 个探测已发出（`Future.wait`），点"停止"只 `_sub?.cancel()`，最多还要等这批 700ms 超时结束才真正安静。
- **证据**：`connect_page.dart:122-129`（`_stopScan` 只 cancel 订阅）、`lan_scanner.dart:132-141`（`Future.wait` 批探测 + 700ms 超时）。
- **影响**：仅体验细节（停止后仍有短暂网络活动），无数据风险。
- **建议修法**：给 `discover()` 传取消标记（如 `shouldStop` 回调）或在 `_stopScan` 时通过 `StreamSubscription.cancel()` 后的 `onCancel` 提前跳出循环。

## 6. 未核实 / 不确定

- **未真机、未抓包、未运行**：本轮未启动后端/Flutter/前端，未跑 `flutter test`/`flutter analyze`，未执行任何 `scripts/mobile-*.ps1`。P0 的**利用链路是源码推断**（Leader 已要求在文档中标注），没有实测伪造应答、没有抓包确认 token 真的到达伪造主机 —— 建议第 2 轮在测试机上用最小 PoC 验证后再定最终定级。
- **UDP 广播在真机校园网/热点下的实际到达率未测**：`CHANGE_WIFI_MULTICAST_STATE` 权限已声明（`AndroidManifest.xml:6`），但代码没有申请 `MulticastLock`；定向广播（`lan_scanner.dart:213-224`）能否绕过 Android 的 Wi-Fi 省电策略，需要在真机上验证（历史证据只有 README.md:49 "实测有效"，指的是 adb reverse 隧道而非广播）。
- **Sa-Token Redis key 名未直接验证**：README.md:112 写 `Authorization:login:token:*`；我核实到的是依赖（`pom.xml:82-83`）+ Redis 配置（`application-dev.yml:11-22`）+ `token-name: Authorization`（`application.yml:34`），**没有**查 `docker exec ershou-redis keys '*'` 或 `TTL`，故 key 的确切前缀属推断（未核实）。
- **`allowBackup` 默认为 true 属规范推断**：本工程与合并产物都未声明该属性；未做 `adb backup`/`bmgr` 实测。
- **后端 `LanDiscoveryResponder` 的防火墙/端口占用行为未实测**：`LanDiscoveryResponder.java:52-60` 端口占用时只打 warning 并不影响启动，这一分支未验证。
- **跨网段/多网卡下 `_candidates()` 的实际耗时未测**：按 `lan_scanner.dart:261-288` 最坏估算（多张网卡 + 4 个兜底网段 × 254 主机 / 64 一批 × 700ms）可达十几秒到数十秒，属于我按代码推算的下限/上限，未实测。
- **未核实 9010 未打通时的用户可见表现**：`net_image.dart:66` 只有 `broken_image` 图标兜底，没有"图片通道未打通"的提示文案，这是读代码所得；真机上到底是持续转圈还是裂图未验证。
- **iOS/其他平台**：本工程只有 `android/`，不做 iOS；`usesCleartextTraffic` 相关问题在 iOS 上不存在（未核实，因为无 iOS 工程）。

## 7. 下一轮计划

1. **P0 修复（需改源码，Leader 已列入第 2 轮第一批）**：换服务器时二次确认 + `ApiClient` 目标 host 绑定（token 只发往用户确认过的 origin）；文件范围：`ershou_app/lib/core/network/api_client.dart`、`lib/core/providers.dart`、`lib/ui/pages/connect_page.dart`。同一轮补最小 PoC 验证（本地起伪造 UDP/HTTP 服务观察是否收到 Beacon 头），把 P0 从"源码推断"升级为"已复现"或降级。
2. **P1 系列（需改源码/脚本/文档）**：连接页提示补 9010 并加 9010 可达性探测；冷启动"地址在但探测失败 → 进连接页自动扫描"；复活 `forget()` 给个"清除当前地址"入口。
3. **凭证加固（需改源码/配置）**：`android:allowBackup="false"`；评估 `flutter_secure_storage`（需加依赖，会动 `pubspec.yaml`，属"改依赖"动作，请 Leader 决定）；`networkSecurityConfig` 只对局域网 IP 放明文。
4. **文档对齐（需改文档，不改代码）**：README.md:56、README.md:57、`ershou_app/README.md:11,15` 按第 5 节 P2/P1 结论修正；`功能清单.md:317` 无误无需改。
5. **补测试（需改测试文件）**：为"换服务器不清 token"和"广播应答来源校验"各加一条单元测试（用假 socket/假 dio 适配器），把 P0 的修复钉住；现有 `test/item_feed_stale_refresh_test.dart` 与 `test/lan_probe_test.dart` 可作模板。
6. （低优先）`ApiClient` 在 HTTP 401 时也触发 `onUnauthorized`；`LanScanner` 消费后端下发的 `ips` 做补探。

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-14 | 收到 | Leader 指派：第 1 轮只读核实，只写本文件，禁止改源码/启动服务/`flutter build`，结论必须带 `路径:行号`，未验证的写第 6 节 |
| 2026-10-14 | 提交 | 早报 P0：局域网发现可被同网设备伪造 → 换服务器不清 token → `Authorization: Bearer` 明文外发；附 5 行证据链（`lan_scanner.dart:165-198`、`server_config_service.dart:74-99`、`api_client.dart:75-83`、`providers.dart:53-56`、`connect_page.dart:157-170` + `profile_page.dart:86-90`） |
| 2026-10-14 | 收到 | Leader 裁决：**采纳，定级 `P0（安全）`**，列入第 2 轮修复第一批；要求 ① 级别写 `P0（安全）` 并注明利用前提与"仅源码推断、未经真机/抓包验证"；② 补齐"换服务器 token 不被清除"的具体代码行并贴出 `providers.dart` 中"token 非空即算登录"那一行；③ 区分登录前/登录后两条路径；④ `usesCleartextTraffic` 与 10 年会话作为**同根因放大器单独列条**，不与主条混写。已按此写进第 5 节（P0 主条 + P1-A/P1-B 放大器）：`providers.dart:353` 已贴出，"token 不被清除"证据链见 P0 证据第 6 点（`providers.dart:56/333-336/453`、`server_config_service.dart:40-49`、`connect_page.dart:158`） |
| 2026-10-14 | 提交 | 本轮完成回报（≤15 行，含 Top 发现与证据、下一轮建议）已 `send_message` 给 `lead` |
| 2026-10-14 12:52 | 收到 | **Leader 验收结论：通过**（8 节齐全；代码地图 50 行带行号；结论 C1–C16 全部带证据；P0×1 / P1×4 / P2×6；第 6 节 8 项未核实显式列出）。我独立复读并确认 P0 主条两处核心证据：`lan_scanner.dart:184-194`（只认 `app=='ershou-server'`，host 取源地址、port 取包内字段）、`api_client.dart:75-83`（token 无条件附加到当前 baseUrl，无 host 绑定）。 |
| 2026-10-14 12:52 | 收到 | 第 2 轮第一批（**P0 修复轮，可写轮**）：① 先做**最小 PoC**（本机起伪造 UDP 应答 + 伪造 HTTP 返回 `code:0` 的服务），把"仅源码推断"升级为"已复现"；② `ApiClient` 增加目标 host 绑定（token 只发往用户确认过的 origin）；③ 换服务器加二次确认并显示 IP。三项由 Leader 建独立共享任务并指定**唯一写者**（默认仍交给你），届时允许改 `lib/core/network/api_client.dart`、`lib/core/providers.dart`、`lib/ui/pages/connect_page.dart`，并补单测钉住。 |
| 2026-10-14 12:52 | 收到 | P1-A（`usesCleartextTraffic` + 明文本地落盘 + 未声明 `allowBackup`）与 P1-B（10 年会话 + 关闭活跃超时）**维持 P1、保持独立条目**，与 P0 同批但分开验收。P1 三条（连接页提示补 9010、冷启动自动扫描、复活 `forget()`）与 3 处文档不一致列入第 2 轮次批。 |
| 2026-10-14 12:52 | 收到 | 你提出的"改为 `flutter_secure_storage` 会动 `pubspec.yaml`"属**改依赖动作**：第 2 轮需 Leader 单独批准后再动，本轮不得改。 |
