# 登录注册与会话 · auth-member 记录

| 项 | 值 |
| --- | --- |
| 成员 | `auth-member` |
| 负责功能 | 手机号登录注册（轻注册/无实名）、Sa-Token 会话、学校校区、个人资料编辑、我的黑名单 |
| 记录文件 | `智能体团队/01-auth-登录注册与会话.md` |
| 本轮状态 | `已完成` |
| 更新时间 | `2026-10-14 12:34` |
| 覆盖功能编号 | 功能清单 #1 手机号登录注册、#2 学校与校区、#19 个人资料编辑、#24 我的黑名单 |
| 共享任务 | `task-1`（只读核实轮） |

## 1. 本轮状态

- 状态：**已完成**（第 1 轮只读核实轮）。负责人：`auth-member`。完成时间：2026-10-14 12:34。
- 覆盖功能（对照 `功能清单.md`）：#1（`功能清单.md:12`）、#2（`功能清单.md:13`）、#19（`功能清单.md:31`）、#24（`功能清单.md:41`）。
- 本轮纪律自证：**只读了源码/配置/文档，没有修改任何源码、配置、脚本、文档，也没有启动后端/前端、没有跑任何 probe/smoke 脚本**；唯一写入的文件就是本记录文件。
  - 核对方式：`git -C E:\ershou status --short`（2026-10-14 12:33）显示工作区已有 `M README.md`、`M ershou-server/.../common/cache/CacheProperties.java`、`M .../common/cache/JsonCache.java`、`M .../module/item/service/ItemService.java`、`M 开发文档.md`、`M 开发日志.md`，以及未跟踪的 `?? scripts/audit-cache.mjs`、`?? 智能体团队/`。**这些都不是本成员产生的**（本轮我没写过上述任何文件），如需追溯请找对应写者。
  - 本记录引用的文档行号 = 上述工作区**当前内容**的行号（其中 `README.md`/`开发文档.md` 处于未提交修改状态，见第 6 节第 9 条）。

## 2. 功能边界

- **做什么**：手机号 + 短信验证码的"登录即注册"（轻注册、无实名/学号/身份证）；Sa-Token 会话的签发、携带、失效与退出；学校/校区字典的读取与用户归属校验；个人资料编辑（昵称/头像/学校校区/小铺名/小铺简介，手机号不可改）；拉黑/解除拉黑、我的黑名单列表、双向拉黑标记。
- **不做什么**：不做真实短信通道对接（代码里只有 mock + TODO，`UserService.java:79`）；不做实名/学籍认证；不做密码登录、第三方登录、找回密码；不做 `schools/apply` 学校申请（文档提到但代码不存在，见 P2-4）；不做个人主页的展示逻辑（`GET /users/{id}/profile` 的页面由个人主页负责，本文件只核实它的鉴权放行与拉黑/联系方式字段）；拉黑在**私聊侧**的拦截细节归 `chat-member`（本文件只引用 `blockedEither` 的证据）；拉黑在**商品侧**的可见性过滤属未实现项（`功能清单.md:421`，归 item/map）。

## 3. 代码地图

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 后端·认证入口 | `ershou-server/src/main/java/com/ershou/campus/module/user/controller/AuthController.java:19` | `@RequestMapping("/api/v1/auth")`；`/sms-code` `:26-30`、`/login` `:32-36`、`/logout` `:38-43` |
| 后端·用户入口 | `.../module/user/controller/UserController.java:21` | `/api/v1/users`；`GET /me` `:28-32`、`PATCH /me` `:34-38`、`GET /{id}/profile` `:40-44`、`POST /{id}/block` `:46-51`、`DELETE /{id}/block` `:53-58`、`GET /blocks` `:60-64`、匿名取 viewer `:66-73` |
| 后端·登录/注册 | `.../module/user/service/UserService.java:65-82` | `sendSmsCode`：每日限额 → 生成/取 mock 码 → 写 Redis（TTL 5 分钟） |
| 后端·登录/注册 | `.../module/user/service/UserService.java:84-132` | `login`：校验码 `:85-89`、删码 `:90`、按 phone_hash 查号 `:92-93`、建号 `:95-114`、老用户更新 `:115-127`、`StpUtil.login` `:129-131` |
| 后端·会话 | `.../module/user/service/UserService.java:134-144` | `getById`（查不到抛 10002）、`currentUser()`（`/users/me` 的实现） |
| 后端·资料 | `.../module/user/service/UserService.java:206-251` / `:253-287` / `:289-299` / `:302-308` | `updateProfile` / `applySchoolCampus` / `normalizeAvatar` / `auditProfileText` |
| 后端·VO 组装 | `.../module/user/service/UserService.java:310-347` | `toVO`：脱敏手机号 `:328-336`、学校校区名走字典缓存 `:337-345` |
| 后端·公开主页 | `.../module/user/service/UserService.java:165-196` | `publicProfile`：不含联系方式；`blocked` `:193`、`blockedByPeer` `:194` |
| 后端·黑名单 | `.../module/user/service/UserBlockService.java:39-56` / `:58-65` / `:68-75` / `:78-85` / `:107-148` | `block` / `unblock` / `blocked` / `blockedEither` / `list` |
| 后端·实体与模型 | `.../module/user/entity/SysUser.java:11-38`、`dto/LoginDTO.java:8-18`、`dto/SmsCodeDTO.java:8-11`、`dto/UserProfileUpdateDTO.java:16-32`、`vo/UserVO.java:8-31`、`vo/PublicUserVO.java`、`vo/UserBlockVO.java:19-43`、`vo/LoginVO.java:8-11` | 表映射、入参校验、出参结构 |
| 后端·学校校区 | `.../module/school/controller/SchoolController.java:18`、`:25-29`、`:31-36` | `/api/v1/schools`；`GET /schools`（status=1）、`GET /{schoolId}/campuses`（school+status=1） |
| 后端·字典缓存 | `.../common/cache/DictionaryCache.java:94-108` | school/campus 单条 GET，TTL=`ershou.cache.dict-ttl`（1h，`application.yml:75`） |
| 后端·加解密 | `.../common/util/PhoneCrypto.java:20-25` / `:27-35` / `:37-49` / `:51-59` / `:62-67` | 密钥、AES/ECB 加密、SHA-256、解密、脱敏 |
| 后端·Redis key | `.../common/util/RedisKeys.java:16-18` / `:20-22` / `:108-114` | `ershou:sms:code:{phone}`、`ershou:sms:limit:{phone}:{yyyyMMdd}`、`ershou:dict:school/campus:{id}` |
| 后端·鉴权 | `.../common/config/SaTokenConfig.java:15-26` | `SaInterceptor` + `SaRouter.match("/api/**").notMatch(...)` 白名单 |
| 后端·异常与响应 | `.../common/exception/GlobalExceptionHandler.java:24-27`、`common/result/ResultCode.java:7-16`、`common/result/Result.java:11,18,34`、`common/filter/TraceIdFilter.java:23-30` | 未登录→10002、验证码错→20001、封禁→20002、限流→10004；traceId 落 MDC→响应体+响应头 |
| 后端·配置 | `ershou-server/src/main/resources/application.yml:1-2` / `:33-54` / `:78-80` / `:92-94`；`application-dev.yml:11-22`；`application-local.yml` | 端口 8080；sa-token 段；`ershou.crypto.phone-key`；`ershou.sms.{mock,mock-code}`；Redis 连接 |
| 后端·依赖 | `ershou-server/pom.xml:23`、`:74-84` | sa-token 1.39.0 + `sa-token-spring-boot3-starter` + `sa-token-redis-jackson` |
| 数据库 | `sql/schema.sql:13-23` / `:25-35` / `:71-102` / `:428-436` | `school` / `campus` / `sys_user`（`phone` `:73`、`phone_hash` `:74`、`uk_user_phone_hash` `:98`）/ `user_block`（`uk_block_pair` `:434`） |
| 数据库·校区数据 | `sql/migration-guet.sql:11`、`:17-45` | 学校=桂林电子科技大学；campus 1=金鸡岭、2=花江、3=六合路 |
| Web·登录页 | `ershou-web/src/views/LoginView.vue:42-57` / `:59-84` / `:148-150` | 发码、登录（`:73-74` 传默认学校校区）、"固定 123456"文案 |
| Web·会话 | `ershou-web/src/stores/user.ts:30-42` / `:45-50` / `:74-79`；`src/utils/request.ts:35-47` / `:71-77` / `:90-93` / `:108-110` | 登录→写 localStorage→拉 /users/me；退出只清本地；token 键 `ershou_token`；10002/401 跳登录 |
| Web·常量与路由 | `ershou-web/src/utils/constants.ts:3-4` / `:6-7`；`src/router/index.ts:167-189` / `:97-102`；`src/composables/useAuthGuard.ts:10-15` | 默认学校 1/校区 2、DEV_SMS_CODE；路由守卫；`/blocked`；未登录跳登录 |
| Web·资料编辑 | `ershou-web/src/components/ProfileEditDialog.vue:66-98` / `:125-145` / `:251-253`；`src/api/user.ts:10-17` / `:72-74` | 学校/校区联动、提交 PATCH /users/me、手机号不可改文案 |
| Web·黑名单 | `ershou-web/src/views/ProfileBlockView.vue:38-47` / `:49-73` / `:116-118`；`src/api/user.ts:82-92`；`src/views/ProfileView.vue:137` | 列表、解除（单向二次确认）、"对方也在拉黑你"标记、入口 |
| Web·信用等级 | `ershou-web/src/views/ProfileView.vue:64-74`；`src/views/UserProfileView.vue:26-30`；`src/api/auth.ts:46` | 「我的」与用户主页两套映射（不一致，见 P1-2） |
| Web·退出 | `ershou-web/src/layouts/DefaultLayout.vue:72-92`；`src/views/ProfileView.vue:205-218` | 两处退出都只清本地 |
| App·登录页 | `ershou_app/lib/ui/pages/login_page.dart:33-53` / `:55-80` / `:83-89` / `:245` | 发码、登录（不传 school/campus）、deviceId 由 token 派生、固定码文案 |
| App·会话 | `ershou_app/lib/core/providers.dart:374-378` / `:380-413` / `:444-455` / `:457-465`；`lib/core/storage/local_store.dart:12` / `:19-27` / `:121-125`；`lib/core/network/api_client.dart:39,55,77` | 冷启动读回 token、登录写 SharedPreferences、失效清理、退出调后端、请求头带 token |
| App·仓储 | `ershou_app/lib/data/api_repository.dart:10-13` / `:15-33` / `:35-41` / `:43-45`；`lib/data/api_repository2.dart:151` / `:516-521` | 发码/登录（默认校区 1/1）/me/logout；地图默认校区 2；拉黑与解除封装 |
| App·我的页 | `ershou_app/lib/ui/pages/profile_page.dart:55-72` / `:110-113` | 菜单（无编辑资料、无黑名单）、退出登录 |
| 文档 | `功能清单.md:12-13,31,41` / `:51-61` / `:293-305` / `:356-363`；`README.md:104-131` / `:219` / `:403-416` / `:573-576`；`开发文档.md:298-312` / `:499-501` / `:1204-1237` / `:1637-1663` / `:1393` | 与代码逐条对照结果见第 4/5 节 |

## 4. 已核实结论

> 全部为**静态核实**（读代码/配置/SQL/文档），本轮不启服务，未跑接口；"运行时"结论见第 6 节。

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | 验证码：key `ershou:sms:code:{手机号}`、TTL **5 分钟**、值为 6 位随机数（mock 时固定）；发送侧只有"每手机号每日累计 **10 次**"一条限流，超出抛 **10004**；**没有**服务端 60 秒重发冷却 | `RedisKeys.java:16-18`、`UserService.java:66-75`（`increment`→首次 `expire(1 天)`→`>10` 抛 RATE_LIMIT）、`ResultCode.java:11` |
| 2 | 校验：与缓存字符串比对成功才 `redis.delete(key)`；**失败不删除、不计失败次数** | `UserService.java:85-90` |
| 3 | 登录即注册：按 `phone_hash` 查号；新号昵称=`同学`+手机后 4 位、school/campus 缺省 1/1、creditScore=100、creditLevel=1、publishLimit=5、chatLimit=20、status=1、deviceAccountCount=1；老号只更新 `lastLoginAt`（传了 deviceId 则一并更新）；`status==3` 抛 **20002** | `UserService.java:92-127`、`LoginDTO.java:16-17`、`ResultCode.java:14` |
| 4 | 会话 = Sa-Token 1.39 + `sa-token-redis-jackson`（Redis 存储）：`token-name=Authorization`、`token-prefix=Bearer`、`token-style=uuid`、`timeout=315360000`（10 年）、`active-timeout=-1`、`is-concurrent=true`、`is-share=false` | `pom.xml:23,74-84`、`application.yml:33-54`、`application-dev.yml:11-22` |
| 5 | 鉴权范围：`/api/**` 全部过 `SaInterceptor`，白名单 = `auth/**`、`categories`、`schools/**`、`map/**`、`trade-points/**`、`search/status`、`items`、`items/*`、`users/*/profile`；未登录访问受保护接口由全局异常处理统一返回 **10002**（HTTP 200 + 业务码） | `SaTokenConfig.java:15-25`、`GlobalExceptionHandler.java:24-27`、`ResultCode.java:9` |
| 6 | Web 会话：token 存 **localStorage**（键 `ershou_token`），每次请求注入 `Authorization: Bearer <token>`；收到业务码 10002 或 HTTP 401/403 时清 token 并跳 `/login?redirect=` | `request.ts:35-47`、`:71-77`、`:90-93`、`:108-110` |
| 7 | App 会话：token 存 **SharedPreferences**（键 `ershou.token`），冷启动 `AuthNotifier.build` 读回，`apiClientProvider` 建 client 时装上；请求头带 token | `local_store.dart:12,19-27`、`providers.dart:374-378,56`、`api_client.dart:39,55,77` |
| 8 | 退出登录：**App 调** `POST /auth/logout`（失败也清本地）；**Web 不调**，只清 localStorage + 内存 | `api_repository.dart:43-45` ← `providers.dart:457-465`；`stores/user.ts:74-79` + `DefaultLayout.vue:83-88`、`ProfileView.vue:205-218`（`ershou-web` 全目录 grep `auth/logout` 无命中） |
| 9 | 手机号落库：`phone` = AES/ECB/PKCS5Padding + Base64 密文，`phone_hash` = SHA-256 小写十六进制；查询按 `phone_hash`，DB 有唯一索引 `uk_user_phone_hash`；对外只给 `mask()` 脱敏号（前 3+`****`+后 4） | `PhoneCrypto.java:27-35,37-49,62-67`、`UserService.java:92-98,328-336`、`schema.sql:73-74,98` |
| 10 | 学校/校区接口公开且只返回启用项；校区按 `school_id` + `status=1` 过滤；资料编辑时后端二次校验"学校/校区存在且启用"+"校区属于该校"+"换学校不换校区时原校区必须仍属新校" | `SchoolController.java:25-36`、`SaTokenConfig.java:17`、`UserService.java:253-287` |
| 11 | 资料编辑是**局部更新**（null=不改，空串=清空），用 `LambdaUpdateWrapper`（`updateById` 清不掉值）；昵称/小铺名过 `ContentAuditService`（scene=`profile`，禁售词抛 **50001**）；头像只接受 `/`、`http://`、`https://` 开头；手机号不在可改字段里 | `UserService.java:206-251,289-299,302-308`、`UserProfileUpdateDTO.java:16-32`、`ProfileEditDialog.vue:251-253` |
| 12 | 黑名单存储：`user_block(user_id, blocked_user_id)`，唯一索引 `uk_block_pair` 保证重复拉黑幂等（捕获 `DuplicateKeyException`）；不能拉黑自己（抛 **60004**）；解除按 `(user_id, blocked_user_id)` 精确删除 | `UserBlockService.java:39-65`、`schema.sql:428-436`、`ResultCode.java:37` |
| 13 | 黑名单 VO 字段 = `id / blockedUserId / nickname / avatar / creditScore / identityFlag / shopName / schoolName / campusName / createdAt / blockedByPeer`，**不含手机号**；`blockedByPeer` = "对方也在拉黑你"（实现上一次性查出"谁拉黑了我"再 `contains`，避免循环查库）；用户主页同时下发 `blocked`/`blockedByPeer`，且匿名访问时两者恒为 false | `UserBlockVO.java:19-43`、`UserBlockService.java:119-147`、`UserService.java:191-195`、`UserController.java:66-73` |
| 14 | 拉黑的"双向生效"在私聊侧确实成立：发起会话与发送消息都判 `blockedEither`（任一方向即拒，抛 **70006**） | `UserBlockService.java:78-85`、`ChatService.java:90,377`、`ResultCode.java:49` |
| 15 | 链路追踪：每请求生成 16 位 traceId → MDC + 响应头 `X-Trace-Id`，业务响应体 `Result.traceId` 取自 MDC（`spring.jackson.default-property-inclusion=non_null`，为空时不输出） | `TraceIdFilter.java:23-30`、`Result.java:11,18,34`、`application.yml:18` |
| 16 | 两端登录可见的默认值：Web 登录固定传 `schoolId=1 / campusId=2` 且页面写死"验证码固定 123456"；App 登录不传学校校区（后端缺省 1/1）并在页面写死 123456 | `constants.ts:3-7`、`LoginView.vue:50,73-74,149`、`login_page.dart:42,61,70-74,245`、`LoginDTO.java:16-17` |

## 5. 发现的问题

### P0（阻断）

**P0-1 固定验证码 `123456` 是"默认生效、无 profile 门禁"的后门 → 任意手机号账号可被接管**

- **现象**：`ershou.sms.mock=true` 与 `ershou.sms.mock-code="123456"` 写在**基础配置** `application.yml`（所有 profile 共用），不是 `application-dev.yml`；后端 `sendSmsCode` 在 mock 时把固定码写进 Redis，`login` 只要求"提交的码 == 缓存里的码"。仓库根 `.env`（我按变量名逐个核对，无任何 SMS 变量）与 `.env.example` 都没有关闭它，仓库里也不存在 `prod` profile。两端 UI 还主动把固定码告诉用户。
- **证据**：`application.yml:92-94`（`ershou.sms.mock: true` / `mock-code: "123456"`）、`UserService.java:58-62`（默认值同样是 true/123456）、`UserService.java:74-75`（mock 时写入固定码）、`UserService.java:85-89`（只比对缓存值）、`.env`（无 `ERSHOU_SMS_*`/`ershou.sms.*` 项）、`constants.ts:6-7`、`LoginView.vue:50,149`、`login_page.dart:42,245`。
- **影响**：任何能访问后端的人（`application.yml:2` 默认监听 8080，移动端通过局域网直连）都可以：① `POST /api/v1/auth/sms-code {"phone":"<任意手机号>"}`；② `POST /api/v1/auth/login {"phone":"<同一手机号>","code":"123456"}` → 拿到该手机号账号的 Sa-Token 令牌。因"登录即注册"，对未注册手机号则会直接建号。若运营账号用手机号登录，同样可被登录（运营判定见 `AdminAuthService.java:22-41`）。且服务端与仓库都没有针对 `/auth/login` 的限流层（全仓 grep 无通用限流 Filter/Interceptor；仓库无 nginx 配置）。
- **建议修法**：① 把 mock 相关配置与实现移到 dev，只在 `dev` profile 生效（`@Profile("dev")` + 独立 `SmsCodeSender` 实现，或 `@ConditionalOnProperty` + 启动断言）；② 非 dev 环境强制 `mock=false`，缺失真实短信服务商时**启动即失败**，不要静默降级；③ 前端删除硬编码的 123456 文案，改为由后端下发"是否 mock"标志；④ 按 P1-3 给登录加失败次数与发送冷却限流。

### P1（影响体验或数据）

**P1-1 Web「退出登录」不调后端，服务端会话仍有效**

- **现象**：App 的退出会调 `POST /auth/logout`；Web 的退出只清本地（token/profile/userInfo + localStorage），两个调用点都没调后端；`ershou-web` 里连 `/auth/logout` 的接口封装都没有。
- **证据**：`api_repository.dart:43-45`、`providers.dart:457-465`（App 有）；`stores/user.ts:74-79`、`DefaultLayout.vue:83-88`、`ProfileView.vue:205-218`（Web 只清本地）；`AuthController.java:38-43`（后端接口存在）；`ershou-web` 全目录 grep `auth/logout` 无命中。
- **影响**：用户点"退出登录"后旧 token 仍可用（有效期 10 年，`application.yml:45`）。共用设备、token 曾被复制/截图/日志泄露的场景下，"退出"这个安全动作实际没生效；服务端也无法借退出清理会话。
- **建议修法**：Web 增加 `logoutApi()`，在 `userStore.logout()` 里先 `await logoutApi()`（放 `try/catch`，失败也继续清本地），与 App 行为对齐；也可顺带在退出时清 Pinia 其他 store（购物车/未读已在部分调用点处理）。

**P1-2 学生端「我的」信用等级映射与后端/DB/运营端完全相反（含把风险用户显示成"信用极好"）**

- **现象**：后端唯一口径是 `score>=95→1(优秀) / >=80→2(良好) / >=60→3(一般) / <60→4(风险)`；学生端「我的」页把 1 显示成"信用一般"、3 显示成"信用优秀"、4 显示成"信用极好"，而同项目的用户主页用的却是正确映射。新注册用户 creditScore=100、creditLevel=1，登录后看到的是"信用一般"。
- **证据**：`AdminUserService.java:126-134`、`SysUserMapper.java:14-15`（CASE 同口径）、`AdminViewAssembler.java:281-286`（1=优秀…4=风险）、`schema.sql:85`（列注释同）；**错误映射**：`ProfileView.vue:64-69`（`{1:'信用一般',2:'信用良好',3:'信用优秀',4:'信用极好'}`，`:70-74` 使用）；**正确映射**：`UserProfileView.vue:26-30`；新用户初值：`UserService.java:104-105`。
- **影响**：信誉信息对外展示反了——满信用新用户被显示为"信用一般"，信用分 <60 的风险用户被显示为"信用极好"，直接影响交易决策与投诉口径；同一 App 两个页面互相矛盾。
- **建议修法**：`ProfileView.vue` 的 `CREDIT_LEVEL_TEXT` 改为 `{1:'优秀',2:'良好',3:'一般',4:'风险'}`；更稳妥的做法是后端在 `UserVO` 里直接下发 `creditLevelText`（运营端已这么做，`AdminViewAssembler.java:105`），前端只渲染不再各自映射；同时把 `api/auth.ts:46` 的 `creditLevel: string` 修正为 `number`（当前靠 `typeof` 兜底才没崩，`ProfileView.vue:70-74`）。

**P1-3 验证码校验无失败次数限制（可暴力试码），且服务端无 60 秒重发冷却**

- **现象**：登录校验失败时不计数、不删除/失效验证码；Redis 里只有"验证码"与"每日发送计数"两个短信 key；服务端无通用限流；"60 秒内只能发一次"只存在于两端前端的倒计时按钮里（可直接绕过）。
- **证据**：`UserService.java:85-90`（失败直接抛错，无计数/无删除）、`RedisKeys.java:16-22`（无 attempt/cooldown key）、全仓 grep `RateLimit|限流|Interceptor` 只命中 Sa-Token 与 MyBatis 分页/WS 握手（无 REST 限流）、`LoginView.vue:30-40`、`login_page.dart:43-48`（前端倒计时）。
- **影响**：真实短信模式下，攻击者在验证码 5 分钟 TTL 内可对同一手机号提交无限次 6 位码（100 万空间）；发送侧的"每日 10 次"限制的是**发**，不是**试**，拦不住爆破。与 P0-1 叠加时风险更高。
- **建议修法**：新增 `ershou:sms:try:{phone}`（如 5 次失败即 `DELETE` 验证码并短期封禁该号登录），并在 `sendSmsCode` 增加 `ershou:sms:cooldown:{phone}`（TTL 60s，`SETNX`）节流，使实现与 `开发文档.md:309` 的描述一致。

**P1-4 App 新用户默认校区 = 1（金鸡岭），与 App/Web 地图默认校区 = 2（花江）冲突 → 用 App 发布的商品在地图上不出现；且 App 无法修改校区**

- **现象**：App 登录不传学校/校区 → 后端取缺省 1/1；而 App 的地图/点位/选点默认 `campusId=2`，Web 端登录与地图都用 2。商品的 `campus_id` 取发布者 `user.campus_id`，而地图点位的"在售商品数"按 `item.campus_id = 查询校区` 过滤。
- **证据**：`login_page.dart:70-74`（不传 school/campus）→ `providers.dart:383-384` + `LoginDTO.java:16-17`（缺省 1/1）；`api_repository2.dart:151`（`defaultCampusId = 2`）及 `:153,161,227,279`、`publish_page.dart:825`（选点不传 campusId）；`constants.ts:3-4` + `LoginView.vue:73-74` + `MapView.vue:305,322`（Web = 2）；`ItemService.java:312-313`（商品继承发布者学校/校区）；`TradePointService.java:434-436`（地图点位商品统计 `eq(Item::getCampusId, campusId)`）。
- **影响**：App 新用户（`campus_id=1`）用 App 默认选点（花江 campus 2 的点位）发布商品后，商品的 `campus_id=1` 与点位所属校区 2 不一致 → 该商品不会计入/显示在该校区地图的点位气泡里（"我发布了但地图上看不到"）；Web 新用户默认 2 与地图一致。同一个"登录即注册"动作在两个端落进不同校区，数据口径分叉。另外 App 完全没有"编辑资料"入口，用户无法自行纠正校区。
- **建议修法**：① 统一默认校区（App 登录缺省改为 2，或登录页提供学校/校区选择，与 `constants.ts:3-4` 同源）；② `PointPickerPage`/发布页用"当前登录用户的 campusId"而不是硬编码 `MapRepository.defaultCampusId`；③ App 我的页补"编辑资料"入口（至少能改学校/校区），或明确在文档里写"App 不支持改校区"。此条跨 item/map 模块，请 Leader 指派归属。

**P1-5 封禁到期不自动解封：`ban_until` 只写不读**

- **现象**：登录只判 `status == 3`，从不读 `ban_until`；`ban_until` 仅由运营设置接口写入、在运营用户管理页展示；没有定时任务或其它读取点。运营按"默认 7 天"封禁后，状态会一直是封禁。
- **证据**：`UserService.java:116-118`（只判 status）、`AdminUserService.java:79-87`（写 status + banUntil）、`AdminUserVO.java:26`（仅展示）、`SysUser.java:35`（字段）、`module/system/task/ScheduledTasks.java` 无封禁相关任务（grep `ban` 无命中）、`功能清单.md:289`（"默认 7 天"）。
- **影响**：7 天封禁实际等于"永久封禁直到人工解封"，与运营界面/文档语义不符，会产生"到点了还登录不了"的客诉，且加重运营负担。
- **建议修法**：登录时若 `status==3` 且 `ban_until != null && ban_until < now`，视为已到期（放行并可回写 `status=1, ban_until=null`）；或加定时任务扫描到期解封。归属 admin 模块，请 Leader 指派。

**P1-6 手机号 AES 密钥是公开默认值，且全仓未配置 `PHONE_AES_KEY`**

- **现象**：密钥默认 `__REDACTED_PHONE_KEY__`（16 位），`.env`/`.env.example` 都没有 `PHONE_AES_KEY`，`PhoneCrypto` 构造器也兜底同一常量；加密算法为 AES/ECB（无 IV，确定性）。
- **证据**：`application.yml:80`、`PhoneCrypto.java:20-25,29,53`、全仓 grep `PHONE_AES_KEY|phone-key` 仅命中上述两处。
- **影响**：任何拿到数据库备份/只读权限的人都能用仓库里的默认密钥批量解密全部手机号（个人敏感信息），"手机号加密存储"的防护等于失效；AES/ECB 还会让相同明文产生相同密文（同号重复已由 `phone_hash` 唯一索引挡住，但同前缀等模式仍可分析）。
- **建议修法**：`.env` 与 `.env.example` 显式声明 `PHONE_AES_KEY=<随机 16 位>`，并在启动时断言"密钥 ≠ 默认值"，否则启动失败；中期改为 AES-GCM（随机 IV 随密文存储）+ 密钥轮换方案。**是否升级为 P0 请 Leader 裁定**（我按"需要 DB 权限才能利用"评为 P1）。

### P2（一致性 / 文档过期）

**P2-1 `开发文档.md` 的"鉴权设计"与代码不符（token key、TTL、续期方式、注解）**

- **现象/证据**：`开发文档.md:302` 写 `ershou:auth:token:{userId}` + "TTL 7 天（滑动续期）"，实际是 Sa-Token 自己的 key 结构 `Authorization:login:token:{token}`（`application.yml:41` 注释、`README.md:112,131`），TTL 固定 315360000 秒、`active-timeout=-1`（`application.yml:45,48`）→ **没有滑动续期**；`开发文档.md:499` 同 :302；`开发文档.md:303` 写 `@SaCheckLogin`/`@SaCheckRole("admin")` 注解，实际全仓无这两个注解（grep 无命中），鉴权是 `SaTokenConfig.java:15-25` 的 `SaInterceptor` + 控制器内 `AdminAuthService.requireAdminId()`（`AdminAuthService.java:44-51`）；`开发文档.md:1639` 示例 `timeout: 604800`，实际 315360000。
- **影响**：按文档排查会话问题会查错 Redis key、误以为 7 天过期/会自动续期。
- **建议修法**：更新 :302/:499/:1639 为真实 key 与 TTL，删掉"滑动续期"，:303 改为"拦截器 + 控制器内校验"。

**P2-2 `功能清单.md` #1 的会话 TTL 描述过期**

- **现象/证据**：`功能清单.md:58` 写 `Authorization:login:token:{token}` → 用户ID（**TTL 7 天**）；实际 10 年（`application.yml:45`，`README.md:114` 已是 10 年）。同行的 `ershou:sms:code`（5 分钟、校验后立即删除）、`ershou:sms:limit`（每日 10 条）与代码一致（`UserService.java:66-75,90`）。
- **影响**：文档自相矛盾（同一仓库 README 说 10 年、功能清单说 7 天）。
- **建议修法**：把 :58 的 TTL 改为 315360000 秒（10 年），并补一句"改配置不会给已有会话续期，需重新登录"（`README.md:129-131` 已有该说明）。

**P2-3 `开发文档.md` 说"同手机号 60 秒内只能发一次"，代码没有**

- **现象/证据**：`开发文档.md:309`；实际 `UserService.java:64-82` 只有"每日累计 10 次"，`RedisKeys.java:16-22` 无 60 秒 key。另外第 11 次请求虽然被拒（10004），`increment` 已经 +1（`UserService.java:67-73`），即"被拒绝的请求也消耗当日额度"。
- **影响**：与 P1-3 同源；文档读起来像已有节流，实际没有，容易被误判为"限流已实现"。
- **建议修法**：按 P1-3 补 60 秒冷却（推荐），或至少把文档改成"仅每日 10 次"。

**P2-4 `开发文档.md` 列了不存在的接口 `POST /schools/apply`**

- **现象/证据**：`开发文档.md:1393`（认证接口表含 `POST /schools/apply`）；`SchoolController.java:25-36` 只有两个 GET；`schema.sql:17` 的 `school.source` 注释"2=用户申请"无任何写入方（全仓无 apply 实现）。
- **影响**：按文档实现前端调用会 404 / 10002。
- **建议修法**：删除该行或标注"未实现"。

**P2-5 `README.md` 说移动端没有拉黑 API 封装，实际已有**

- **现象/证据**：`README.md:415-416` 写"移动端还没有黑名单页，而且 `api_repository2.dart` 里连拉黑/解除的 API 封装都没有"；实际 `api_repository2.dart:516-521` 已有 `block`/`unblock`。仍属实的是"移动端没有黑名单页"（`ershou_app` 全目录 grep "黑名单" 无命中；我的页菜单也没有入口，`profile_page.dart:55-72`）。
- **影响**：低估移动端进度，可能重复开发。
- **建议修法**：把 :415-416 改为"移动端已有拉黑/解除 API 封装（`api_repository2.dart:516-521`），但**没有黑名单页**（无处查看/解除）"。

**P2-6 `功能清单.md` #19 未标注"个人资料编辑仅 Web 可用"**

- **现象/证据**：`功能清单.md:31,297` 只写 Web 载体（`ProfileView.vue`/`ProfileEditDialog.vue`）；App 侧无 `PATCH /users/me` 调用（`ershou_app` grep `users/me` 只命中 `api_repository.dart:37` 的 GET；我的页菜单无编辑入口，`profile_page.dart:55-72`）。
- **影响**：两端功能覆盖被高估；App 用户改不了昵称/头像/校区（与 P1-4 互相加重）。
- **建议修法**：在 #19 标注"仅 Web 端；App 端未实现（已知缺口）"。

**P2-7 风控字段拿不到有效值：`device_id` 缺失/不稳定，`device_account_count` 从不更新**

- **现象/证据**：Web 登录不传 `deviceId`（`LoginView.vue:70-75`、`LoginDTO.java:18`）→ 新用户 `device_id` 为 NULL（`UserService.java:107`）；App 的 `deviceId` 由**本地 token** 的 hash 派生（`login_page.dart:83-89`），而每次登录都会发新 token（`application.yml:51-52` `is-share=false`）且首次登录时 token 为空 → 同一设备的指纹每次登录都变；`device_account_count` 只在注册时写死 1（`UserService.java:108`），全仓无更新/聚合处（grep 仅 `SysUser.java:31`、`AdminUserVO.java:30` 的存取展示）。
- **影响**：“同设备账号数/设备指纹”类风控数据不可用（马甲号识别拿不到真实值），运营页展示的也是常量 1。
- **建议修法**：App 改用稳定标识（安装时生成 UUID/ANDROID_ID 落 SharedPreferences，与 token 解耦）；Web 也生成持久化 UUID 上报；注册/登录时按 `device_id` 聚合并维护 `device_account_count`。

**P2-8 头像允许任意 `http(s)://` 外链，与"只收上传接口产出"的说法不符**

- **现象/证据**：`UserService.java:290-298` 只校验"以 `/` 或 `http://`/`https://` 开头"即通过，不校验是否属于本站存储前缀（`ershou.storage.public-base-url`，`application.yml:122`）；`功能清单.md:303` 表述为"只接受 `/static/**` 或 `http(s)://`（上传接口产出）"。
- **影响**：用户可把任意外链塞进头像（追踪像素、失效图、指向第三方内容），绕开"头像走内容审核/上传"的初衷。
- **建议修法**：头像白名单校验存储前缀（或只允许 `/static/**` 与 `public-base-url` 前缀），其余抛 10001。

**P2-9 拉黑不校验目标用户是否存在，会落脏数据**

- **现象/证据**：`UserBlockService.block` 只判 `targetId != null` 与"不能拉黑自己"（`:39-45`），不查目标用户；`user_block` 无外键（`schema.sql:428-436`）→ `POST /users/999999/block` 会成功落库；黑名单页只能显示"用户 #999999"（`ProfileBlockView.vue:50,111`）。
- **影响**：黑名单出现指向不存在用户的记录，列表渲染降级、清库后残留；也给了"批量探测用户 id 是否存在"以外的无意义写放大。
- **建议修法**：`block` 内先 `selectById(targetId)`，不存在则抛 10001"用户不存在"。

## 6. 未核实 / 不确定

1. **未做任何运行时验证（纪律要求本机不启服务）**：第 4 节所有"会话/验证码/接口"结论都是读代码+配置+SQL 得到的**静态结论**；token 真实 TTL、后端重启后会话是否仍在、10002/20001/10004 的真实响应体、验证码 5 分钟过期，均**没有实跑**。
2. **`POST /auth/logout` 在"无 token/过期 token"时的行为未核实**：Sa-Token 1.39 是抛 `NotLoginException`（→10002）还是静默成功，我既没有实跑，也没有反编译 jar 核对（依赖来自外部仓库，`pom.xml:74-84`）。因此 P1-1 的修法需按"失败也清本地"实现。
3. **"Sa-Token 存 Redis、key 前缀 `Authorization:login:token:`"未直接验证**：只核实了依赖（`pom.xml:80-84`）、Redis 连接配置（`application-dev.yml:11-22`）与仓库内两处说明（`application.yml:40-41` 注释、`README.md:112,131`）；**没有连 Redis 执行 `KEYS/TTL`**。
4. **每日 10 次的时间窗语义未核实**：代码是"首次 increment 时 `expire(1 天)`"（`UserService.java:67-70`）＋ key 带自然日（`RedisKeys.java:20-22`），两者并存；到底是"自然日"还是"首次发送后 24 小时"为准，取决于首次发送时刻，我没实跑确认，也没核对与 `开发文档.md:309`"每日"的严格一致性。
5. **P1-4 的后果边界未核实**：我只确认地图点位商品统计按 `item.campus_id = campusId` 过滤（`TradePointService.java:434-436`）；**没有验证**这种 `campus_id=1` + 点位属 campus 2 的商品是否会在别处（如首页列表、商品详情、搜索）出现异常 —— 首页列表请求不带 campusId（`api_repository.dart:92-102`），影响面需 item/map 负责人确认。
6. **App 是否存在我没找到的学校/校区设置页**：结论基于 grep（`编辑|\.patch|schools|campusId|users/me`）与 `profile_page.dart` 全文；我没有逐页通读 `ershou_app/lib/ui/pages` 下所有文件，不排除有未挂到"我的"菜单的隐藏入口。
7. **P0-1 能否提权到运营账号未核实**：能否用手机号+123456 登录**运营账号**，取决于运营账号是否配置了可登录的 `phone`/`role_flags`；我没有连数据库查 `sys_user`（未连 MySQL）。
8. **`sql/schema.sql` 是否等于线上库的真实结构未核实**（未连库比对 `information_schema`）；`credit_level` 默认值在 schema 里是 2（`schema.sql:85`）而代码新用户写 1（`UserService.java:105`），实际以代码写入为准，但历史存量用户的口径我没查数据。
9. **文档类结论以"工作区当前内容"为准**：`README.md`、`开发文档.md` 在 `git status` 中处于已修改未提交状态，我无法区分这些改动是历史遗留还是本轮他人所为；我引用的行号是**当前工作区行号**。
10. **`is_share=false` 导致"每次登录发新 token"这一 Sa-Token 语义**是按配置项含义与仓库注释（`application.yml:51-52`）理解的，没有实跑对比两次登录的 token 是否真的不同。

## 7. 下一轮计划

1. **[需改源码·后端·P0]** 关闭固定验证码后门：`ershou.sms.mock` 仅在 dev profile 生效 + 非 dev 启动断言 + 前端文案改为后端下发。涉及 `application.yml:92-94`、`UserService.java:58-62,74-77`、`constants.ts:6-7`、`LoginView.vue:50,149`、`login_page.dart:42,61,245`。**需 Leader 指定唯一写者与回归方式（我建议同时给 20001/10004 的负向用例）。**
2. **[需改源码·后端·P1]** 验证码安全加固：失败计数（5 次即失效验证码）+ 发送侧 60 秒冷却（`SETNX`），涉及 `UserService.java:64-90`、`RedisKeys.java:16-22`；顺带修数据语义"被拒绝的发送也 +1"。
3. **[需改源码·Web·P1]** ① 退出登录补调 `POST /auth/logout`（`stores/user.ts:74-79`）；② 修 `ProfileView.vue:64-69` 信用等级映射（与 `UserProfileView.vue:26-30`、后端口径对齐），并把 `api/auth.ts:46` 类型改对。两处都在 Web，可同一写者。
4. **[需改源码·App+后端·P1]** 统一默认校区并修 P1-4：App 登录缺省校区改为 2（或登录页可选），选点/发布用当前用户 `campusId`，App 补"编辑资料/校区"入口；涉及 `providers.dart:383-384`、`api_repository.dart:19`、`publish_page.dart:825`、`profile_page.dart:55-72`。**跨 item/map 模块，请 Leader 裁定归属。**
5. **[只读复核]** 请 Leader 裁定两件事的级别与归属：① P1-6（`PHONE_AES_KEY` 未配置、默认密钥）是否升为 P0；② P1-5（`ban_until` 到期不解封）归 admin-member。另请核对第 1 节列出的那些**非本成员**的工作区改动（`git status`）。

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-14 12:31 | 收到 | Leader 指派（`task-1`）：第 1 轮只读核实 #1/#2/#19/#24，只写本文件，禁止改源码/启服务/跑脚本 |
| 2026-10-14 12:32 | 提交 | `team_task_update` claim `task-1`（revision 1 → 2，状态 in_progress） |
| 2026-10-14 12:34 | 提交 | 本记录完成（8 节齐全，状态=已完成）；`team_task_update` action=complete |
| 2026-10-14 12:35 | 提交 | `send_message` 回报 `lead`：状态 / Top 发现（P0-1 固定验证码后门、P1-1 Web 退出不调后端、P1-2 信用等级反了、P1-4 App 默认校区冲突）/ 建议下一轮动作 |
| 2026-10-14 12:52 | 收到 | **Leader 验收结论：通过**（8 节齐全；16 条结论、16 条问题均带 `路径:行号`）。P0-1 已由 Leader 独立复核并**升级确认为全环境生效**：`application.yml:92-94` 属基础配置，`application-dev.yml`/`application-local.yml`/`.env` 均无覆盖 → 爆炸半径 = 所有 profile（不是"仅开发环境"），列入第 2 轮第一批必修。 |
| 2026-10-14 12:52 | 收到 | **裁定 ①** P1-6（`PHONE_AES_KEY` 默认密钥）：**维持 P1**（利用需 DB 读权限，不构成无前提接管）。但要求把「启动断言密钥 ≠ 默认值」与 P0-1 的修复**并入同一批**（都是"配置兜底值不得进生产"）。**裁定 ②** P1-5（`ban_until` 只写不读）：**维持 P1，记录归属保留在本文件**；写入侧在 user 域、读取/解封侧在 admin 域，已要求 admin-member 交叉核对后台封禁路径，第 2 轮由 Leader 指定唯一写者。**裁定 ③** P1-4（App 默认校区 1/2 冲突）：**维持 P1，跨 item/map 模块**；第 2 轮由 Leader 排期并指定唯一写者（倾向"统一常量 + 发布用当前用户 campusId"一处改完）。 |
| 2026-10-14 12:52 | 收到 | 第 2 轮排期（本文件相关）：P0-1 固定验证码（含 dev-only + 启动断言）、P1-1 Web 退出登录补 `POST /auth/logout`、P1-2 信用等级映射修正、P1-3 验证码失败计数 + 60s 冷却。修复轮为**可写轮**，届时由 Leader 逐个任务指定唯一写者，勿自行改动源码。 |
