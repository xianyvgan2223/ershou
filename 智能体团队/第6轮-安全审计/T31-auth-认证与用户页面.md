# T31 安全审计：认证 / 用户 / 个人主页 / 设置（auth-member）

| 项 | 值 |
| --- | --- |
| 任务 | `task-31`（第 6 轮 · 安全审计 · 只读） |
| 负责人 | `auth-member` |
| 审计时间 | 2026-10-21 11:08（Asia/Shanghai） |
| 代码基线 | `git HEAD = 681a5e5 使用springcloud重构项目`；工作区另有他人未跟踪文件 `智能体团队/第6轮-优化建议.md`（非本成员产出） |
| 审计范围 | Web 认证/用户/个人主页/设置类页面；单体 `ershou-server` 的 auth/user 链路 + Sa-Token 配置；云版 `ershou-cloud/ershou-user-service`、`ershou-cloud-gateway` 鉴权；相关配置/凭据 |
| 方法 | **静态代码审计为主**；只读命令（读文件、grep、`git ls-files`、端口监听检查）。**未修改任何源码/配置/数据库；未启动任何服务；未做爆破/DoS/写操作**。 |
| 动态验证 | ❌ 未进行。审计时 8080/8081/8082/8083/8088/6380/8848 **均未监听**，故未发起任何 HTTP 请求（后文“验证状态”中“待动态验证”指需启动服务后才可确认）。 |
| 结论 | **High 5 · Med 7 · Low 9 · Info 4**；Top3 见第 3 节 |

> 定级口径：High = 可直接绕过认证/接管账号/批量泄露；Med = 显著增加被攻破概率或泄露敏感数据；Low = 需前置条件或纵深防御缺口；Info = 非安全的功能/一致性发现。

---

## 1. 页面与接口清单

### 1.1 枚举说明（先修正范围）

- 任务书中提到的 `RegisterView`、`SettingsView`、`UserHomeView` **在当前代码中不存在**：注册在 `LoginView` 内完成（登录即注册）；“设置”即「我的」页里的 `ProfileEditDialog` 资料编辑弹窗；用户主页现名 `UserProfileView`。全量页面见 `ershou-web/src/views/`（glob 结果：无上述三个文件），路由见 `ershou-web/src/router/index.ts:19-223`。
- 后端**不存在 `module/auth` 包**：认证入口在 `module/user/controller/AuthController.java`，鉴权拦截在 `common/config/SaTokenConfig.java`；`module/system/**` 仅有 `ScheduledTasks.java`（定时任务，与本类页面无关）。
- 认证/用户页面共 5 个页面/组件 + 1 个全局组件（顶栏用户菜单），清单如下。

### 1.2 页面清单表

| # | 页面 / 组件 | 路由 / 位置 | 调用的接口 | 鉴权方式 | 备注 |
| --- | --- | --- | --- | --- | --- |
| P1 | 登录 / 注册页 `LoginView.vue` | `/login`（`router/index.ts:20-25`） | `POST /auth/sms-code`、`POST /auth/login`；登录成功后 `GET /users/me`（`stores/user.ts:30-42,45-50`） | **页面公开**；上述 3 个接口均无需登录 | 注册=首次登录建号；页面直接展示“验证码固定 123456”（`LoginView.vue:149`） |
| P2 | 我的（个人中心）`ProfileView.vue` | `/profile`（`router/index.ts:99-104`） | `GET /users/me`、`GET /users/blocks`（仅计数）、`GET /items?...`、`GET /favorites`、`GET /cart`、面交待办 | 前端守卫 `requiresAuth`（`router/index.ts:231-253`）+ 请求头 `Authorization: Bearer`（`utils/request.ts:71-77`）；服务端 Sa-Token/网关校验 | 展示脱敏手机号（`ProfileView.vue:243`），不含明文 |
| P3 | 编辑资料弹窗 `ProfileEditDialog.vue` | `/profile` 内弹窗（`ProfileView.vue:258,402`） | `PATCH /users/me`、`GET /schools`、`GET /schools/{id}/campuses`、`POST /files/upload?biz=avatar` | 登录态（Bearer）；上传接口服务端 `StpUtil.checkLogin()`（`FileController.java:29`） | 昵称/头像/学校校区/小铺名/小铺简介；手机号不可改 |
| P4 | 我的黑名单 `ProfileBlockView.vue` | `/blocked`（`router/index.ts:105-110`） | `GET /users/blocks`、`DELETE /users/{id}/block` | 路由 `requiresAuth` + Bearer | 解除为单向（`ProfileBlockView.vue:49-73`） |
| P5 | 用户主页 `UserProfileView.vue` | `/user/:id`（`router/index.ts:68-74`） | `GET /users/{id}/profile`（公开）、`GET /items?sellerId`（公开）、`POST /users/{id}/block`、`DELETE /users/{id}/block`、`POST /chat-sessions` | **页面与主页接口公开**；拉黑/私聊动作登录后可用（前端 `ensureLogin`：`composables/useAuthGuard.ts:10-15`，`UserProfileView.vue:50,68`） | 公开主页刻意不含联系方式（`PublicUserVO.java:7-35`） |
| P6 | 顶栏用户菜单（全局）`DefaultLayout.vue` | 所有页面顶栏 | 退出登录**不调任何接口**（仅清 localStorage） | —（纯前端动作） | `DefaultLayout.vue:75-95`；`stores/user.ts:73-79` |
| P7 | 关联页面（不属本类，交叉引用） | `/chat`、`/admin/users`、`/console/platform/**` | 聊天页内也有拉黑动作（`ChatView.vue:377-383`）；运营用户管理/平台运营台 | 见各自类别 | 归 `chat-member` / `admin-member` 审计，本报告仅在涉及认证链路时引用 |

### 1.3 后端鉴权落点（三套并存，需分别看）

| 入口 | 鉴权实现 | 证据 |
| --- | --- | --- |
| 单体 `:8080`（Web 默认代理目标） | `SaInterceptor` 拦截 `/api/**`，白名单：`/api/v1/auth/**`、`categories`、`schools/**`、`map/**`、`trade-points/**`、`platform/shop/**`、`search/status`、`items`、`items/*`、**`users/*/profile`**；其余 `StpUtil.checkLogin()` | `common/config/SaTokenConfig.java:15-29`；`ershou-web/vite.config.ts:20`（默认 proxy `http://127.0.0.1:8080`） |
| 网关 `:8088`（cloud 模式） | `SaReactorFilter` 先按 `PublicPaths.isPublic(method,path)` 判公开，否则 `StpUtil.checkLogin()`，未登录 **HTTP 401 + `{code:10002}`**；通过后 `X-User-Id` 注入下游（并**删除客户端自带**的同名头） | `ershou-cloud-gateway/.../SaTokenReactorConfig.java:32-60`；`.../config/PublicPaths.java:19-66`；`.../filter/AuthGlobalFilter.java:29-41`；`ershou-cloud/nacos-configs/ershou-gateway.yaml:31-39` |
| 云 `user-service :8081` / `order-service :8083` | **不做本地鉴权**：`UserContextInterceptor` 把请求头 `X-User-Id` 直接写入 ThreadLocal，业务用 `UserContext.requireUserId()` | `ershou-cloud-common/.../web/UserContextInterceptor.java:13-17`；`.../web/ApiHeaderSupport.java:26-29`；`ershou-user-service/.../controller/UserController.java:41-49`；`ershou-order-service/.../controller/MeetupOrderController.java:37-48` |
| 版本 | Sa-Token 1.39；token 随机 UUID、`is-share=false`（每次登录新 token） | `ershou-server/pom.xml:23`；`application.yml:53`；`nacos-configs/ershou-user-service.yaml:40` |

---

## 2. 漏洞表

> 证据均为 `文件:行号`（工作区当前内容）。验证状态：`已静态核实` = 代码/配置证据确凿；`待动态验证` = 需启动服务/抓包确认；“端到端验证待做”。

### High（5）

| ID | 等级 | 页面/接口 | 证据 | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **H-01** | **High（Critical 级）** | 登录：`POST /api/v1/auth/login`（含单体与云两套，且云版连验证码都不用发） | 单体：`application.yml:102-104`（`sms.mock: true`+`mock-code: "123456"` 写在**基础配置**，非 dev profile；`UserService.java:58-62,74-77`）。云版：`nacos-configs/ershou-user-service.yaml:49-52`（同样是 `mock: true`/`123456`）；**且 `login` 直接用 `mockHit = smsMock && mockCode.equals(dto.getCode())`，命中即通过、完全不查 Redis**：`UserService.java:103-109`。前端把固定码印在页面上：`LoginView.vue:50,149`、`constants.ts:7` | **任意能访问服务的人（本机/局域网）可登录任意手机号账号**：云版只需 `POST /auth/login {"phone":"<受害者手机号>","code":"123456"}`（无需先发码）；单体版先 `POST /auth/sms-code` 再同样登录。含运营账号（若运营用手机号登录）。10 年 token 使接管长期有效（H-02） | 已静态核实（端到端待动态验证） | ① `mock` 只在 dev profile 生效（`@Profile`/`@ConditionalOnProperty` + 启动断言，非 dev 时 mock=false）；② 云版删除 `mockHit` 直通分支，mock 码也必须走 Redis 校验；③ 前端文案改由后端下发“是否 mock”；④ 与 H-02/M-01 一起做。 |
| **H-02** | High | 会话：`/auth/login`、`/auth/logout`、`GET /users/me`；Web 顶栏/我的页退出按钮 | token 寿命：单体 `application.yml:33-54`（`timeout: 315360000`=10 年、`active-timeout: -1`、`is-concurrent: true`、`is-share: false`）；网关 `nacos-configs/ershou-gateway.yaml:31-39`、user-service `nacos-configs/ershou-user-service.yaml:33-41` 同值。**注销只删当前 token**：单体 `AuthController.java:38-43`、云 `UserService.java:153-161`；**Web 退出根本不调后端**：`stores/user.ts:73-79`、`DefaultLayout.vue:75-95`、`ProfileView.vue:205-215`，`api/auth.ts` 全文件无 logout 封装；`is-concurrent=true` 意味着历史 token 不因重新登录失效；无“全端下线/吊销”能力 | token 一旦泄露（XSS、共享电脑、日志/抓包、Redis 泄露见 H-04），攻击者可**长期（10 年）冒充**；用户点“退出登录”后旧 token 仍有效；服务端无法主动踢人（无黑名单/版本号机制） | 已静态核实 | ① 有效期改为 7~30 天 + 滑动续期/`active-timeout` 合理值；② Web 退出必须调 `POST /auth/logout`；③ 提供“全部设备下线”（`StpUtil.logout(loginId)` 或用户级 token 版本号）；④ 敏感操作再校验。 |
| **H-03** | High | 云版 `GET /api/v1/users/me`（直连 `:8081`）；同类：`order-service :8083` 建/查面交单 | `UserController.java:41-49`：**先看 `UserContext`（=`X-User-Id` 头）再看 Sa-Token**；`ApiHeaderSupport.java:26-29` 直接 `Long.parseLong(header)`；`CloudCommonAutoConfiguration.java:42-56` 全局注册拦截器；服务端口对所有网卡监听：`ershou-user-service/src/main/resources/application.yml:10-11`（8081）、order `:8083`；网关注入逻辑 `AuthGlobalFilter.java:29-41`（只有**经过网关**时才删客户端伪造头） | 任何能访问宿主机/局域网 8081 的人可 `curl -H "X-User-Id: <任意ID>" :8081/api/v1/users/me` **读取任意用户资料**（脱敏手机号/学校/角色标记/发布额度）；同理 `:8083` 可伪造任意用户创建/查看面交单（order-service `MeetupOrderController.java:39,47`）——**接口越权（IDOR）/认证绕过** | 已静态核实（直连可达性待动态验证） | ① 服务间信任改为签名（网关对下游加内部签名头/HMAC，或 mTLS）；② 服务本身也校验 Sa-Token（与单体一致）；③ 端口绑定 127.0.0.1 或只允许网关网段；④ 至少对 `X-User-Id` 只在“内部来源”生效（如内网 IP 校验 + 网关签名）。 |
| **H-04** | High | 配置/凭据：`ershou-cloud/nacos-configs/*.yaml`、`application-local.yml`、Redis `:6380` | **明文密码被 git 跟踪**：`git ls-files` 结果包含 `ershou-cloud/nacos-configs/ershou-gateway.yaml`、`.../ershou-item-service.yaml`、`.../ershou-order-service.yaml`、`.../ershou-user-service.yaml`、`ershou-server/src/main/resources/application-local.yml`；密码明文：`nacos-configs/ershou-user-service.yaml:10,19`、`ershou-gateway.yaml:12`、`ershou-item-service.yaml:10,19`、`ershou-order-service.yaml:10,19`、`application-local.yml:5,8,11`（且该文件注释自称“已被 .gitignore 忽略，不会提交”，实际**已被跟踪**：`application-local.yml:1` 对照 `git ls-files`）。Redis 端口对外发布：`docker-compose.yml:28-32`（`requirepass`+`6380:6379`）。Sa-Token 会话存 Redis db0（`application-dev.yml:16`、`SaTokenRedisConfig.java:44-75`） | 拿到仓库（含历史提交）或该密码的人可 `redis-cli -h <host> -p 6380` 读取 **db0 里所有 `Authorization:login:token:*` 会话**→ 直接冒充任意用户；也可读/改业务数据。同一密码长期复用（MySQL/Redis/MinIO） | 已静态核实（Redis 可达性待动态验证） | ① 立即轮换 MySQL/Redis/MinIO 密码；② 从 git 历史清除凭据（filter-repo/BFG）并入库 `.gitignore` 校验（`application-local.yml` 改名为 `application-local.secret.yml` 之类）；③ Nacos 配置用环境变量/密钥管理注入；④ Redis 绑 127.0.0.1、加 ACL、会话库与业务库物理隔离。 |
| **H-05** | High | 基础设施面板（与认证直接相关：可改 `sms.mock`/密钥/密码） | `ershou-cloud/docker/docker-compose.yml:19`（`NACOS_AUTH_ENABLE=false`）、`:23-25`（8848/9848 对外）；`:42-46`（RabbitMQ **guest/guest** + 15672 对外）；`:81-82`（Sentinel Dashboard 8858 对外）；根 `docker-compose.yml:81-82`（ES 9200/9210 关闭安全认证且对外）、`:49-51`（MinIO 控制台对外）、`:31-32`（Redis 对外） | Nacos 无认证且可写：任何能访问 8848 的人可**下发/修改配置**（把 `ershou.sms.mock` 打开、改 `phone-key`、改数据库/Redis 密码、把 legacy-uri 指向恶意服务）→ 应用层加固被完全绕过；RabbitMQ guest/guest 可读写消息；ES 可读全量商品/用户索引（若建了用户索引） | 已静态核实 | 演示环境之外必须关闭面板外网暴露（bind 127.0.0.1）、Nacos 开鉴权、RabbitMQ 改强口令、Sentinel/ES 加认证；CI 增加“高危端口对外”检查。 |

### Medium（7）

| ID | 等级 | 页面/接口 | 证据 | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **M-01** | Med | 登录/发码：`POST /auth/sms-code`、`POST /auth/login` | 登录校验失败**不计数、不失效验证码**：单体 `UserService.java:84-90`、云 `UserService.java:103-109`；无图形验证码（全仓 grep `captcha` 无命中）；发码仅按手机号每日 10 次（`UserService.java:64-73`、`UserRedisKeys.java:24-27`），**无 IP/设备维度**；网关限流只覆盖 `GET /items`（`SentinelGatewayConfig.java:39-47`、`ershou-gateway.yaml:46-47`），`/auth/**` 不限流 | 真实短信模式下可对同一手机号**无限次试 6 位码**（5 分钟 TTL 内 1e6 空间）；可对大量手机号各发 10 条/日 → 短信轰炸/成本攻击；无爆破锁定 | 已静态核实 | ① 验证失败计数（如 5 次即失效验证码+短封该号）；② 发码加 60s 冷却 + IP/设备维度限流 + 图形/行为验证码；③ 网关对 `/auth/**` 配置 Sentinel 限流；④ 登录失败写安全审计。 |
| **M-02** | Med | 公开用户主页 `GET /api/v1/users/{id}/profile` | 新用户默认昵称 = `"同学" + 手机号后 4 位`：单体 `UserService.java:99`、云 `UserService.java:118`；该昵称在**未登录可访问**的公开主页上原样展示（`SaTokenConfig.java:27`、`PublicPaths.java:50-52`、`PublicUserVO.java:14-15`、`UserProfileView.vue:102-103`） | 未登录访问者/爬虫可按 id 批量抓取“昵称=同学xxxx”，**间接泄露手机号后 4 位**；与公开的学校/校区/信用分组合后可做定向社工 | 已静态核实 | 默认昵称改为不含手机号的随机串（如“同学+随机 4 位字母数字”）；存量数据清洗；公开主页不展示可推断联系方式的字段。 |
| **M-03** | Med | 手机号加密（存储层） | 密钥默认值写死且仓库内可得：单体 `application.yml:90`（`${PHONE_AES_KEY}`）、云 `nacos-configs/ershou-user-service.yaml`（明文默认值已迁到 `.env`）、`PhoneCrypto.java:20-25`；算法 AES/ECB 无 IV（`PhoneCrypto.java:29,53`）；`.env` 中**未配置** `PHONE_AES_KEY`（grep 结果：无该变量） | 任何拿到 DB 备份/只读权限 + 仓库的人可**批量解密全部手机号**；ECB 确定性会暴露相同明文模式 | 已静态核实 | 强制从密钥管理注入随机密钥（缺失即启动失败）；改用 AES-GCM（随机 IV）；轮换存量密文。 |
| **M-04** | Med | Redis 数据（认证相关） | 短信 key 含**明文手机号**：单体 `RedisKeys.java:16-22`（`ershou:sms:code:{phone}` / `smsLimit`）；云 `UserRedisKeys.java:19-27`（`ershou:user:sms:code:{phone}`）；会话 token 也在此 Redis（db0） | 有 Redis 访问权者（密码见 H-04）可枚举用户手机号清单 + 读取全部会话 token；手机号属个人信息，未加密落缓存 | 已静态核实 | key 用 `phone_hash` 替代明文手机号；Redis 加 ACL/网络隔离；会话与业务分库分账号。 |
| **M-05** | Med | 两套认证入口并存（单体 `:8080` + 网关 `:8088`→user-service `:8081`） | 两套 Sa-Token 配置共享 Redis db0（`SaTokenRedisConfig.java:15-27,44-75`），但**验证码存储不同库/前缀**：单体 db0 `ershou:sms:code:`（`application-dev.yml:16`、`RedisKeys.java:16-18`）vs 云 db1 `ershou:user:sms:code:`（`nacos-configs/ershou-user-service.yaml:20`、`UserRedisKeys.java:19-22`）。`ershou-server` 仍直接监听 8080（`application.yml:1-2`），网关把 legacy 流量反代它（`GatewayRoutesConfig.java:28-29,58`） | ① **安全策略不同步**：在网关/云侧关闭 mock 后，攻击者仍可打 8080 单体（或反之）用另一侧配置绕过；② 用户在 A 端获取的验证码在 B 端登录显示“验证码错误”（功能缺陷，易误判）；③ 攻击面翻倍 | 已静态核实 | 下线旧单体认证入口（网关不暴露 legacy `/auth/**`、8080 只绑 127.0.0.1/加内网 token）；统一 Redis 会话与验证码 key 方案；过渡期让单体验证码读写同一 db/key 前缀。 |
| **M-06** | Med | 公开用户主页 + 登录响应 | `GET /users/{id}/profile` 按**自增 id** 公开可访问，返回昵称/学校/校区/信用/封禁状态/在售数（`UserController.java:40-44`、`PublicUserVO.java:12-35`）；登录响应带 `newUser` 布尔区分新老用户（`UserService.java:131`、`UserVO.java:25`），云版同理（`UserService.java:150,202`） | 可枚举全站用户（id 自增）+ 其学校/信用/状态；配合 H-01 可直接探测“某手机号是否已注册”（newUser）——为撞库/定向攻击提供信息 | 已静态核实 | 公开主页改雪花/不可枚举 id 或对外用 userNo；`newUser` 仅用于 UI 提示时不做语义区分（或仅对本人返回）；增加访问频率限制。 |
| **M-07** | Med | 前端会话存储与 XSS 面 | token 存 `localStorage`（`utils/request.ts:35-47,71-77`），非 HttpOnly Cookie；`index.html:1-17` **无 CSP/安全响应头**；聊天/审核 WS 把 token 放 URL query（`stores/chat.ts:53-54`、`composables/useAdminReviewSocket.ts:74-76`）。本类页面未发现 `v-html`（唯一 `v-html` 在 `components/ItemCard.vue:125`，属商品页），昵称/简介均用 `{{ }}` 转义 | 一旦出现 XSS（含商品页高亮、第三方依赖、未来新页面），攻击者可窃取 10 年有效 token；WS token 可能进入代理/服务端访问日志与浏览器历史 | 已静态核实（XSS 实际可触发性待动态验证） | 加 CSP（至少 `script-src 'self'`，禁 inline）；token 改短寿命 + 刷新机制；WS 鉴权改用一次性 ticket/子协议头而非 URL；对 `ItemCard.vue:125` 的 `v-html` 做白名单净化复核。 |

### Low（9）

| ID | 等级 | 页面/接口 | 证据 | 影响 | 验证状态 | 修复建议 |
| --- | --- | --- | --- | --- | --- | --- |
| **L-01** | Low | 登录页 `/login?redirect=` | `LoginView.vue:77-78` 直接把 query `redirect` 交给 `router.replace()`，未校验。当前 vue-router 只做同源 history 导航，**实际不可外跳到 `https://evil.com`**；但若将来改成 `window.location` 即成开放重定向 | 潜在钓鱼/跳转风险（当前不可直接利用） | 已静态核实（实际外跳不可行） | redirect 只允许以 `/` 开头且非 `//`、`/\\`；或维护白名单路由。 |
| **L-02** | Low | 头像 `PATCH /users/me`、上传 `/files/upload` | 头像只校验“以 `/` 或 `http(s)://` 开头”：`UserService.java:289-299`（`//evil.com/x.png` 也能过）；本地存储模式**不校验文件类型**（`LocalFileStorageService.java:25-45`，直接 `transferTo` 后强制 `.jpg` 名）；MinIO 模式有扩展名白名单+重编码（`MinioFileStorageService.java:83-95`） | 任意第三方 URL 可作为头像 → 追踪像素/对外请求；本地降级模式下可上传任意内容（后缀被强制 .jpg，触发率低） | 已静态核实 | 头像 URL 仅允许本站存储前缀（`public-base-url`/`/static/`），禁止协议相对；本地模式复用 MinIO 的类型校验与重编码。 |
| **L-03** | Low | `POST /files/upload` | `FileController.java:25-32` 仅要求登录，无频率/配额限制（5MB/次，`application.yml` multipart 限制） | 登录用户可无限上传 → MinIO/磁盘滥用（成本型 DoS） | 已静态核实 | 按用户/IP 限流（如 10 次/分钟）、每日配额、图片数量上限。 |
| **L-04** | Low | 发码 | 验证码用 `ThreadLocalRandom`（单体 `UserService.java:74`、云 `UserService.java:82`），非 `SecureRandom` | 理论上可预测性（实际受 TTL/空间限制，危害有限） | 已静态核实 | 改用 `SecureRandom`。 |
| **L-05** | Low | 注册（登录即注册） | `LoginDTO.java:16-18`（单体）/云 `LoginDTO.java:22-24`：`schoolId/campusId` 客户端可传任意值，注册时**不校验存在/启用/归属**（`UserService.java:100-101`）；`deviceId` 无 `@Size` 校验（DB 列 `VARCHAR(64)`：`schema.sql:88`） | 可建号到不存在/停用/他人学校校区（脏数据、统计污染）；超长 deviceId 触发 90001 | 已静态核实 | 服务端校验 school/campus 合法（复用改资料时的校验逻辑）；`@Size(max=64)`。 |
| **L-06** | Low | `POST /users/{id}/block` | 不校验目标用户是否存在（`UserBlockService.java:39-50`），只判 null/自己；`user_block` 无外键（`schema.sql:428-436`） | 可写入指向不存在用户的拉黑记录（脏数据，列表渲染降级） | 已静态核实 | 先 `selectById(target)` 不存在抛 10001。 |
| **L-07** | Low | 网关 | 客户端可自带 `X-Trace-Id`，网关原样复用并回显到响应/下游日志（`TraceIdGlobalFilter.java:30-40`），未校验格式/长度 | 链路 ID 可伪造 → 日志关联误导/短串注入日志 | 已静态核实 | 只接受形如 16 位十六进制的值，否则重生成；下游按“来源=网关”处理。 |
| **L-08** | Low | 网关 CORS | `CorsConfig.java:21-32`：`allowedOriginPatterns("*")` + `allowedMethods/Headers("*")`，但 `setAllowCredentials(false)`（`:27`） | 任意站点可携带“自己拥有的 token”读取接口响应；因不带 cookie，无法直接盗用受害登录态；属过度放开 | 已静态核实 | 收敛到前端域名白名单（dev: 5173，生产: 具体域名）。 |
| **L-09** | Low | 全链路 | 演示环境全 HTTP（无 TLS）；响应无 `X-Content-Type-Options`/`HSTS`/CSP；登录成功/失败无安全审计（仅 `log.info` 用户 id，`UserService.java:114,126`），无异常登录告警 | 局域网内 token/验证码可被嗅探；攻击无迹可查 | 已静态核实 | 生产启用 HTTPS + 安全响应头；登录失败/异地/频次异常写审计与告警。 |

### Info（4，非安全 / 一致性）

| ID | 说明 | 证据 |
| --- | --- | --- |
| I-01 | **云模式下编辑资料功能回归**：`PATCH /api/v1/users/me` 被网关路由到 user-service，而 user-service 只有 `GET /me`，会 405（Web cloud 模式保存资料失败）。非安全问题，但属重构回归 | `GatewayRoutesConfig.java:41`（路由未限制方法）；`ershou-user-service/.../controller/UserController.java:35-38`（只有 GET） |
| I-02 | 云版 `/users/me` 的 `isAdmin` 只认 `role_flags & 8`（`UserService.java:229-232`），**不识别单体的 `ershou.admin.user-ids` 白名单**（单体 `AdminAuthService.java:22-41`）→ 云模式下靠白名单授权的运营看不到后台入口（功能/一致性） | 上述两处 |
| I-03 | 前端 `requiresAuth`/`requiresAdmin` 仅是客户端守卫（`router/index.ts:231-253`），后端也有校验；仅作为“纵深防御说明”，不构成漏洞 | 同左 |
| I-04 | 任务书中的 RegisterView/SettingsView/UserHomeView 不存在；`module/auth` 不存在（认证在 `module/user/controller`，鉴权在 `common/config`） | 见 1.1 节 |

---

## 3. Top3 风险（按利用难度 × 影响排序）

### TOP1 — H-01 固定验证码 123456：零凭证接管任意账号（Critical）
- **利用方式（云模式，2 行命令）**：`POST /api/v1/auth/login {"phone":"<目标手机号>","code":"123456"}` → 直接拿到该账号 token；**无需先请求验证码**（`ershou-user-service/.../UserService.java:103-109` 的 `mockHit` 分支）；单体模式先 `POST /auth/sms-code` 再同样提交（`UserService.java:58-62,74-77`）。
- **为什么最容易**：无需任何前置信息（手机号可从公开主页/商品卖家信息获得）；配置默认开启（单体基础配置 + Nacos 配置都是 true）；前端页面还提示“固定 123456”。
- **影响面**：全站任意用户（含运营账号）；与 H-02 叠加后接管是长期的。

### TOP2 — H-04 凭据入库 + Redis 会话可读：批量接管全部在线用户（High）
- **利用方式**：从仓库（或历史提交）得到 Redis 密码（`nacos-configs/ershou-user-service.yaml:19` 等，均被 git 跟踪）→ 连 `:6380`（`docker-compose.yml:31-32` 对外）→ 读 db0 的 `Authorization:login:token:*` → 用任意 token 调接口；同密码还可连 MySQL 直接读全部手机号密文（配合 M-03 的默认 AES 密钥可解密）。
- **为什么排在 TOP2**：不需要应用层漏洞，一次配置泄露即全量沦陷；`application-local.yml` 注释声称被忽略但实际被跟踪，说明防护假设错误。

### TOP3 — H-02 10 年会话 + 无吊销 + Web 退出不彻底：泄露即长期接管（High）
- **利用方式**：XSS/共享电脑/抓包/日志拿到一次 token → 10 年内有效；用户“退出登录”不删除服务端会话（Web 不调 logout），重新登录也不使旧 token 失效（`is-concurrent: true`）；无“全端下线”能力。
- **影响**：事件响应只能改 Redis 手动删 key，无法让用户自救。

> 紧随其后：**H-03 内部服务信任 `X-User-Id`**（云版 `:8081/:8083` 可直连伪造任意用户身份），属云化新增攻击面，建议与 H-02 一起排期。

---

## 4. 已核实的安全正向结论（避免误报/避免重复修）

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | 用户侧响应**不含手机号明文**：`/users/me` 与登录响应只返回脱敏号（前 3+`****`+后 4）；公开主页与黑名单 VO **完全没有手机号字段** | `UserService.java:328-336,310-347`；`PhoneCrypto.java:62-67`；`PublicUserVO.java:7-35`；`UserBlockVO.java:22-43` |
| 2 | 单体自助接口**无横向越权**：`PATCH /users/me`、`GET /users/blocks`、拉黑/解除都以 `StpUtil.getLoginIdAsLong()` 为操作者，不接受 body/path 指定用户 id | `UserController.java:34-64` |
| 3 | 认证类页面**未发现 v-html/innerHTML**：昵称/小铺简介/学校名全部用 `{{ }}`（Vue 自动转义），仅有商品卡片一处 `v-html`（属商品页，且 ES 高亮已转义）；`nameInitial` 仅取首字符 | grep `v-html|innerHTML|dangerouslyUseHTMLString` 仅命中 `components/ItemCard.vue:125`；`utils/format.ts:172-175` |
| 4 | 头像上传**服务端强制登录**，MinIO 模式下按扩展名白名单（jpg/jpeg/png/webp）+ `ImageIO` 解码 + 统一重编码为 jpg（可防 SVG/伪造图片多形态 XSS）；`biz` 参数过滤非字母字符（防目录穿越） | `FileController.java:25-32`；`MinioFileStorageService.java:83-101`；`LocalFileStorageService.java:31` |
| 5 | token 不可预测：Sa-Token `token-style: uuid`（`application.yml:53`），非 JWT、无“none 算法”问题 | 同左；`pom.xml:23` |
| 6 | 网关**已经**处理了 X-User-Id 伪造（转发前先 `remove` 客户端头再按登录态写入） | `AuthGlobalFilter.java:32-39` |
| 7 | 登录失败提示不区分“手机号是否注册”（统一 20001）；发码接口对已注册/未注册手机号无差异响应 | `UserService.java:85-89`、`ResultCode.java`（SMS_CODE_ERROR） |
| 8 | 手机号 hash 唯一索引可防重复建号（`uk_user_phone_hash`），并发注册时不会静默产生同号多账号 | `schema.sql:74,98` |
| 9 | 日志未打印手机号明文/token/验证码（sms 日志用 `PhoneCrypto.mask`；mock 码日志仅打印公开固定码；`sa-token.is-log: false`） | 单体 `UserService.java:76-81`；云 `UserService.java:84-89`；`application.yml:54` |

---

## 5. 跨模块关联发现（供 Lead 去重/分派，不在本报告展开）

| 关联项 | 说明与证据 | 建议归属 |
| --- | --- | --- |
| 内部服务信任 `X-User-Id` 的**写操作** | `order-service` 创建面交单用 `UserContext.requireUserId()`：`ershou-order-service/.../MeetupOrderController.java:37-48`；直连 `:8083` 可伪造任意用户下单/查询 | H-03 一起修（auth + order 共同） |
| WebSocket token 在 URL | `ershou-web/src/stores/chat.ts:53-54`、`composables/useAdminReviewSocket.ts:74-76`；token 可能进代理/访问日志 | `chat-member` / `admin-member` |
| 基础设施无认证 | Nacos 8848（`NACOS_AUTH_ENABLE=false`）、RabbitMQ guest/guest、Sentinel 8858、ES 无认证：`ershou-cloud/docker/docker-compose.yml:19,23-25,42-46,81-82`；根 `docker-compose.yml:81-82` | 运维/Lead 统一处理（H-05） |
| 明文凭据入库 | `ershou-cloud/nacos-configs/*.yaml`、`application-local.yml`（均被 git 跟踪）；`scripts/**`、`ershou-cloud/docs/demo-script.md`、`.build/**` 也有同密码 | 运维/Lead 统一处理（H-04） |
| 商品页 `v-html` 高亮 | `ershou-web/src/components/ItemCard.vue:124-125`（ES 高亮的信任假设需复核） | `item-member` |
| 运营用户管理接口的手机号展示 | `/api/v1/admin/users` 相关 VO（本报告未展开） | `admin-member` |

---

## 6. 验证方法与限制

- **已执行（只读）**：读代码/配置/SQL/git 元数据；`git ls-files` 核对凭据文件是否入库；`Get-Content .env` 核对密钥变量缺失（值未外泄）；`Get-NetTCPConnection` 核对服务未启动；全仓 grep（`captcha`、`v-html`、`X-User-Id`、`UserContext`、`limit/attempt`）。
- **未执行（受纪律/环境限制）**：
  1. 未启动任何服务（8080/8081/8082/8083/8088/6380/8848 均未监听），**未做任何 HTTP 请求**——H-01/H-03/H-02 的端到端可利用性需启动后验证；
  2. 未连 Redis/MySQL（未验证 db0 中 token key 的可读性、验证码 key 的实际形态）；
  3. 未做爆破、未做 DoS、未做写操作；
  4. Sa-Token 是否默认从 Cookie 读取 token（影响 CSRF 面）**未实测**：配置未显式设置 `is-read-cookie`，代码中无自定义 Cookie 逻辑；若登录响应 `Set-Cookie` 且服务端接受 Cookie 鉴权，则 CSRF 面成立（当前仅依赖 Authorization 头，浏览器跨站表单无法携带）。→ 列为**待动态验证**项。
- **建议的动态验证清单（下一轮，只读级）**：① 启动 cloud 全套后 `POST /auth/login` 用 mock 码（用测试号）确认接管路径；② `curl -H "X-User-Id: 1" :8081/api/v1/users/me`；③ 抓登录响应看是否 `Set-Cookie`；④ 用户 A token 调 `PATCH /users/me` 后从 B 视角确认未串号；⑤ 检查 `/users/me` 是否回显脱敏号、公开主页无联系方式。

---

## 7. 修复优先级建议（供 Lead 排期参考）

| 优先级 | 动作 | 关联 ID | 是否改代码 |
| --- | --- | --- | --- |
| P0（立即） | 关闭默认 mock：dev-only + 启动断言 + 云版删除 `mockHit` 直通 + 前端文案改下发 | H-01 | 是（配置 + 少量代码） |
| P0（立即） | 轮换全部泄露密码；git 历史清除；`.gitignore` 修正（`application-local.yml`）；Redis 绑本机 + ACL | H-04 | 否（运维）+ 配置 |
| P1 | 会话治理：有效期收敛、Web 退出调后端、全端下线、敏感操作再校验 | H-02/M-07 | 是 |
| P1 | 服务间信任：网关签名头 或 服务本地校验 + 端口绑定 127.0.0.1 | H-03 | 是 |
| P1 | 防爆破：失败计数/锁定、发码 60s 冷却、IP 维度限流、图形验证码、`/auth/**` 网限流 | M-01 | 是 |
| P2 | 默认昵称去手机号、公开主页 id 不可枚举、`newUser` 语义收敛 | M-02/M-06 | 是 |
| P2 | AES 密钥注入 + AES-GCM；Redis key 用 hash 替代明文手机号 | M-03/M-04 | 是 |
| P2 | CORS 收敛、CSP/安全响应头、WS ticket 化、上传配额 | L-02/L-03/M-07/L-08 | 是 |
| P2 | 基础设施面板关闭对外/Nacos 鉴权/RabbitMQ 强口令/ES 认证 | H-05 | 否（运维） |
| P3 | 其余 Low（重定向校验、SecureRandom、school/campus 校验、拉黑目标校验、TraceId 校验、审计） | L-01~L-09 | 是 |

---

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-21 11:01 | 收到 | Leader 派工 T31：auth-member 负责「认证/用户/个人主页/设置」安全审计（只读，不改代码），报告写 `智能体团队/第6轮-安全审计/T31-auth-认证与用户页面.md` |
| 2026-10-21 11:02 | 提交 | `team_task_update` claim `task-31`（revision 1→2） |
| 2026-10-21 11:08 | 提交 | 本报告完成（High 5 / Med 7 / Low 9 / Info 4；Top3 = H-01/H-04/H-02）；`send_message` 回报 lead |
