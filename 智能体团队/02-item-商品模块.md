# 商品模块 · item-member 记录

| 项 | 值 |
| --- | --- |
| 成员 | `item-member` |
| 负责功能 | 商品发布（AI 机审/新号限额/图片上传）、浏览搜索筛选排序分页（Elasticsearch）、详情浏览量、下架、编辑、7 天有效期与重新上架 |
| 记录文件 | `智能体团队/02-item-商品模块.md` |
| 本轮状态 | `已完成` |
| 更新时间 | `2026-10-14 12:31`（Asia/Shanghai） |
| 覆盖功能编号 | 功能清单 #3、#4、#5、#6、#6.1、#13、#22 |

## 1. 本轮状态

- **状态**：`已完成`（第 1 轮只读核实轮）。
- **负责人**：`item-member`；共享任务 `task-2`。
- **更新时间**：2026-10-14 12:31（Asia/Shanghai）。
- **覆盖功能编号**（对照 `功能清单.md`）：#3 商品发布、#4 浏览/搜索/筛选/排序/分页、#5 商品详情（浏览量）、#6 商品下架、#6.1 有效期与重新上架、#13 图片存储、#22 商品编辑。
- **纪律遵守**：本轮**未修改任何源码/配置/文档/脚本**，未启动后端/前端/ES，未跑 smoke/probe/bench 脚本；唯一写入的文件就是本记录文件。
- **核实方式**：纯静态阅读源码 + 读 `application*.yml` / `sql/schema.sql` / `.env`（只读），所有结论均带真实 `路径:行号`。
- **本轮结论概览**：见第 4 节 18 条已核实结论；问题集中在第 5 节（P0 无；P1 4 条；P2 7 条）；未核实项显式列在第 6 节。

## 2. 功能边界

- **做什么**：
  - 卖家**发布商品**（`POST /items`）：图片上传（`POST /files/upload`）→ 机审（禁售词拦截/可疑词转人工）→ 新号发布限额 → 落库并登记运营「上架审核」队列；有效期 `expire_at = now + validDays`。
  - **浏览/搜索/筛选/排序/分页**（`GET /items`）：有关键词优先走 Elasticsearch（只回 id + total + 高亮，字段回 MySQL），无关键词或 ES 不可用走 MyBatis-Plus 分页；筛选 categoryId/campusId/sellerId/preferPointId(s)，排序 latest/price_asc/price_desc。
  - **商品详情**（`GET /items/{id}`）：Redis 缓存 + 空值哨兵防穿透 + 重建锁防击穿；浏览量 Redis 计数、定时批量回写。
  - **下架**：卖家自助下架（`POST /items/{id}/offline` → 状态 5）与运营强制下架（`POST /admin/items/{id}/offline` → 状态 8，原因必填）两条路径，均顺手作废挂在该商品上的约面交单。
  - **编辑**（`PUT /items/{id}`）：字段级可选更新；改标题/描述重新过机审；已售出不可改。
  - **7 天有效期与重新上架**：`ScheduledTasks.expireItems()` 每小时 :30 把 `status IN (1,2,3,6)` 且过期的置 7；卖家 `POST /items/{id}/relist` 把 5/7 → 2 并重挂满有效期（不重过机审、不占发布额度）；违规下架(8) 只能走 `apply-relist` 进审核队列。
  - **我的发布**（`GET /items/mine`）与运营商品管理列表（`AdminItemService.page`，含状态/关键词/卖家筛选）。
- **不做什么**（越界即错）：
  - 不负责**审核工作台本身**（`ItemReviewService` 的队列/实时推送/统计、`/admin/reviews` 页面）——那是 `admin-member` 的 #21；本文件只在"商品写路径如何入队/出队"这一层引用它。
  - 不负责**地图点位与合并气泡**（`preferPointIds` 只是本模块支持的一个筛选参数，地图侧的聚合/选点属 `map-member` #7/#25/#26）。
  - 不负责**收藏/购物车/面交单/信用分**（`order-member` #8~#12）；本文件只在"下架/过期时作废挂单"这一层引用 `OrderService.closeActiveOrders`。
  - 不负责 `module/file` 之外的通用存储抽象（`common/storage/**` 属基建，本文件只核实 `biz=item` 的上传链路）。
  - 不负责 `ai_audit_record` 表的**设计归属**（`admin-member` #16 的表；本文件只核实"商品机审结果是否写它"）。

## 3. 代码地图

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 接口（商品） | `ershou-server/src/main/java/com/ershou/campus/module/item/controller/ItemController.java:29` | `GET /items` 列表（公开，登录带 favorited） |
| 接口 | 同上 `:34` | `GET /items/mine` 我的发布（`StpUtil.checkLogin()`） |
| 接口 | 同上 `:42` | `GET /items/{id}` 详情 |
| 接口 | 同上 `:48` | `POST /items` 发布（含机审） |
| 接口 | 同上 `:55` | `PUT /items/{id}` 编辑 |
| 接口 | 同上 `:63` | `POST /items/{id}/offline` 卖家下架 |
| 接口 | 同上 `:71` | `POST /items/{id}/relist` 重新上架 |
| 接口 | 同上 `:78` | `POST /items/{id}/apply-relist` 违规商品二次申请 |
| 接口（搜索） | `.../module/search/controller/SearchController.java:32` | `GET /search/status` 索引状态（公开） |
| 接口 | 同上 `:38` | `POST /search/reindex` 全量重建（`sync=true` 同步等待）——**无 `/search/hot`、`/search/suggest`** |
| 接口（分类） | `.../module/item/controller/CategoryController.java` | `GET /categories`（`DictionaryCache` 缓存） |
| 接口（文件） | `.../module/file/controller/FileController.java:26` | `POST /files/upload?biz=item`，登录 + 返回 `{url}` |
| 服务（核心） | `.../module/item/service/ItemService.java:103` | `page()` → `queryPage()`（ES 优先/降级） |
| 服务 | 同上 `:110` | `queryPage` 分流：`supports()` 命中走 ES，异常按 `fallback-to-db` 决定降级或报错 |
| 服务 | 同上 `:132` | `searchViaEs`：ES 命中 id → `selectBatchIds` 回库 → 再按 `status==2` 过滤 → 高亮回填 |
| 服务 | 同上 `:161` | `pageFromDb`：无关键词列表 / ES 降级；`status=2` + 筛选 + 排序 + `selectPage` |
| 服务 | 同上 `:233` | `detail`：空值哨兵 + `getOrLoad` 重建锁 + 浏览量叠加（**不回写缓存**） |
| 服务 | 同上 `:265` | `pendingViews`：`INCR ershou:item:view:{id}` + dirty Set |
| 服务 | 同上 `:284` | `publish`：新号限额 → 机审 → 落库 → afterCommit 同步 ES/清缓存 → 入审核队列 |
| 服务 | 同上 `:369` | `update`：属主/已售出校验 → `textChanged` 重审 → `updateById` → 入队 |
| 服务 | 同上 `:465` | `offline`：卖家下架 → 状态 5 + 作废挂单 |
| 服务 | 同上 `:502` | `relist`：仅 5/7 → 2，重挂 `validDays`，条件更新兜并发 |
| 服务 | 同上 `:555` | `applyRelist`：仅 8 → 1（进审核队列） |
| 服务 | 同上 `:95` | `ershou.item.valid-days`（默认 7），`getValidDays()` 供前端文案 |
| Mapper | `.../module/item/mapper/ItemMapper.java:17` | `lockStock`：`WHERE id=? AND status=2`，`version=version+1` |
| Mapper | 同上 `:22` | `releaseLock`：`WHERE id=? AND status=3` |
| Mapper | 同上 `:27` | `markSold`：`WHERE id=? AND status IN (2,3)` |
| Mapper | 同上 `:35` | `incrViewBy`：浏览量批量回写（`WHERE id=? AND ? > 0`） |
| Mapper | 同上 `:42` / `:47` | `approveAudit` / `rejectAudit`：`WHERE status=1` |
| Mapper | 同上 `:52` | `forceOffline`：`WHERE status IN (1,2,3,6)` → 8 |
| Mapper | 同上 `:63` | `applyRelist`：`WHERE status=8` → 1 |
| Mapper | 同上 `:68` | `markLiveSnapshot`：上架内容快照（改名检测用） |
| Mapper | 同上 `:85` | `downlist`（审核页下架）：`WHERE status IN (1,2,3,6)` → 5，清机审字段与锁 |
| 实体 | `.../module/item/entity/Item.java:50` | `version` 字段（**无 `@Version` 注解**，见问题 P1-1） |
| DTO | `.../module/item/dto/ItemPublishDTO.java:40` | 图片 `@NotEmpty` + `@Size(max=9)` |
| DTO | `.../module/item/dto/ItemUpdateDTO.java:16` | 全部字段可选（null = 不改），图片 `max=9` |
| DTO | `.../module/item/dto/ItemQueryDTO.java:9` | `pageNum=1/pageSize=20`；`:36` `sort=latest` |
| VO | `.../module/item/vo/ItemVO.java:40` | `highlightTitle` / `highlightDescription`（ES 高亮） |
| 搜索 | `.../module/search/service/ItemSearchService.java:48` | `supports()`：开关+关键词+**无 preferPointId**+ES 健康 |
| 搜索 | 同上 `:66` | `max_result_window=10000` 深分页回落 MySQL（不计熔断） |
| 搜索 | 同上 `:109` | **`status=2` 已下推到 DSL filter** + category/campus/seller term |
| 搜索 | 同上 `:122` | `multi_match`：`title^3` / `categoryName^2` / `description`，`best_fields`+`or` |
| 搜索 | 同上 `:147` | 排序：价格优先 → `publishedAt desc,id desc`；默认带关键词加 `_score desc` |
| 搜索 | 同上 `:134` | 高亮 `pre_tags=<em>` + `encoder: html`（前端 `v-html` 的前提） |
| 索引 | `.../module/search/service/ItemIndexService.java:100` | `@Scheduled("${ershou.search.sync-cron:0 */2 * * * ?}")` 增量兜底 |
| 索引 | 同上 `:144` | `ensureIndex()`：幂等建索引；`indexUsable` 失败则删重建 |
| 索引 | 同上 `:136` | 分词方案优先级：`ik_max_word(+syn)` → `smartcn` → 内置 `ngram(1~2)` |
| 索引 | 同上 `:268` | **`reindexAll()` 先 `delete` 索引再 `ensureIndex()` 全量重放** |
| 索引 | 同上 `:312` | 返回 `indexName/analyzer/indexed/visible/docCount/rebuilt`（**两个数**） |
| 索引 | 同上 `:323` / `:351` | `refreshAndCount()`（全部文档）/ `refreshAndCountOnSale()`（`term status=2`） |
| 索引 | 同上 `:366` | `reindexAllAsync()`：CAS 防重入，异步返回 `started` |
| 索引 | 同上 `:385` / `:412` | `syncById`（回库取数 / 不存在则删文档）、`deleteById` |
| 索引 | 同上 `:473` | `status()`：`enabled/indexName/esAvailable/lastError/watermark/reindexing/docCount/onSaleCount` |
| 索引 | 同上 `:523` / `:535` | 水位读写 `RedisKeys.itemIndexWatermark()` |
| 文档 | `.../module/search/document/ItemDocument.java:15` | 索引文档字段（**无 preferPointId**，故 ES 不能按点位筛） |
| 配置 | `.../module/search/config/SearchProperties.java:17` | 索引名默认 `ershou_item`；`:35` 熔断 15s |
| 熔断 | `.../module/search/service/EsGateway.java:26` | `healthy()`：开关 + 不在熔断窗 + 客户端存在 |
| 文件/存储 | `.../common/storage/MinioFileStorageService.java:30` | `mode=minio`（`matchIfMissing=true`）；`:44` 启动自动建桶 + public-read policy |
| 文件/存储 | 同上 `:83` / `:104` | 校验（≤5MB、jpg/jpeg/png/webp）+ Thumbnailator 压缩（长边 1280、q=0.8、统一 jpg） |
| 文件/存储 | `.../common/storage/LocalFileStorageService.java:20` | `mode=local` 降级落本地；`:31` 只校验非空，**不校验格式/大小/不压缩** |
| 静态访问 | `.../common/config/WebMvcConfig.java:20` | `/static/**` → `file:{local-path}` |
| 定时任务 | `.../module/system/task/ScheduledTasks.java:127` | `ershou.item.expire-cron:0 30 * * * ?` 商品过期下架（状态 1/2/3/6） |
| 定时任务 | 同上 `:169` | `ershou.cache.view-flush-cron:0 */10 * * * ?` 浏览量批量回写 |
| 定时任务 | 同上 `:145` | 过期条件更新再作废挂单；`:155` afterCommit 同步 ES |
| 运营处置 | `.../module/admin/controller/AdminManageController.java:60` | `POST /admin/items/{id}/offline`，`@Valid ReasonDTO`（原因必填） |
| 运营处置 | `.../module/admin/dto/ReasonDTO.java:9` | `@NotBlank(message="请填写原因")` |
| 运营处置 | `.../module/admin/service/AdminItemService.java:191` | `forceOffline` → `itemMapper.forceOffline` + 作废挂单 + 审计日志 |
| 审核页下架 | `.../module/admin/dto/ReviewOfflineDTO.java:10` | 原因**选填**，留空用默认文案（`ItemReviewService.java:260`） |
| 审核队列 | `.../module/admin/service/ItemReviewService.java:104` | `onItemLiveOptimistic`（发布/上架/重挂 入队） |
| 审核队列 | 同上 `:123` | `onContentMaybeChanged`（在售期间改名/改描述 重新入队，只在 status=2 生效） |
| 审核队列 | 同上 `:158` / `:177` | `markLiveSnapshot` 更新快照 + `revision` 累加（一件商品只留一条记录） |
| 审核队列 | 同上 `:582` | `resetOnRelistApply`（违规二次申请重置审核记录） |
| 缓存 key | `.../common/util/RedisKeys.java:24` | `ershou:limit:post:{userId}:{yyyyMMdd}` 新号发布限额 |
| 缓存 key | 同上 `:41` / `:46` / `:51` / `:56` / `:147` | `item:detail:{id}` / `item:null:{id}` / `item:view:{id}` / `item:view:dirty` / `item:index:watermark` |
| 事务钩子 | `.../common/util/TxHooks.java:15` | `afterCommit`：无事务时立即执行（写路径同步 ES 的机制） |
| 分页插件 | `.../common/config/MybatisPlusConfig.java:15` | **只有** `PaginationInnerInterceptor`（`maxLimit=100`），无乐观锁插件 |
| 表结构 | `sql/schema.sql:119` | `item` 表；`:151` `version INT COMMENT '乐观锁'` |
| 表结构 | 同上 `:134` | `images JSON COMMENT '图片URL数组 1-9'` |
| 表结构 | 同上 `:146` | `expire_at` 注释"默认发布后 7 天" |
| 表结构 | 同上 `:155`–`:160` | `idx_item_seller` / `idx_item_campus_status` / `idx_item_category` / `idx_item_lock` / `idx_item_expire` / `idx_item_point` |
| 表结构 | 同上 `:392` | `ai_audit_record` 表（**Java 侧零引用**，见 P1-3） |
| 机审 | `.../module/ai/KeywordContentAuditService.java:26` | `auditText`：禁售词 → `block`；可疑词 → `review`；否则 `pass`；**只打日志不落库** |
| 配置（机审词） | `ershou-server/src/main/resources/application.yml:97` / `:99` | 23 个禁售词（含 2 个重复）/ 6 个可疑词 |
| 配置（存储） | 同上 `:115`–`:126` | `mode: minio`、桶 `ershou`、`max-size-mb: 5`、`image-max-edge: 1280` |
| 配置（ES） | `ershou-server/src/main/resources/application-dev.yml:25` | `uris: ${ES_URIS:http://127.0.0.1:9210}`（**代码默认 9210，9211 来自 .env**） |
| 配置（ES） | 同上 `:30`–`:39` | `ershou.search.*`（enabled/index-name=ershou_item/fallback-to-db/reindex-on-startup/sync-cron/bulk-size/failure-backoff-ms） |
| 配置（端口编排） | `docker-compose.yml:82` | `"${ES_PORT:-9210}:9200"`；`.env` 里 `ES_PORT=9211` |
| Web（首页） | `ershou-web/src/views/HomeView.vue:48` | `fetchItems({pageNum,pageSize,keyword,categoryId,sort})`；`:225` 骨架屏；`:254` 分页器 |
| Web（发布） | `ershou-web/src/views/PublishView.vue:131` | `publishItem(...)`；`:144` 用后端 `statusText` 提示；`:187` `ImageUploader :limit="6"` |
| Web（我的发布） | `ershou-web/src/views/PublishListView.vue:93` | `canRelist`：仅 `OFFLINE(5)/EXPIRED(7)`；`:109` `relistItem`；`:181` 到期倒计时文案 |
| Web（详情） | `ershou-web/src/views/ItemDetailView.vue:130` | `offlineable`；`:224` 有效期只给卖家/运营看；`:496` `ItemEditDialog` |
| Web（编辑） | `ershou-web/src/components/ItemEditDialog.vue:247` | 共用编辑弹窗（`:limit="6"`） |
| Web（上传） | `ershou-web/src/components/ImageUploader.vue:69` | 前端预校验（image/* 且 ≤5MB）；`:83` 读 `data.url` |
| Web（API） | `ershou-web/src/api/item.ts:81` | `fetchItems` / `:116` `updateItem` / `:121` `offlineItem` / `:129` `relistItem` / `:134` `fetchMyItems` |
| Web（API） | `ershou-web/src/api/file.ts:13` | `buildUploadUrl` → `/api/v1/files/upload?biz=` |
| Web（工具） | `ershou-web/src/utils/format.ts:191` / `:200` | `expireText`（ceil 天数）/ `expireUrgent`（<1 天） |
| Web（URL 同步） | `ershou-web/src/composables/useQuerySync.ts:40` | 筛选条件 ↔ 路由 query 双向同步（商品页在用） |
| App（首页） | `ershou_app/lib/ui/pages/home_page.dart:91` | 搜索 `onSubmitted` → `itemFeedProvider.search`；`:132` 排序；`:188` 分类胶囊 |
| App（发布/编辑） | `ershou_app/lib/ui/pages/publish_page.dart:21` | 同一页兼编辑模式（`PUT /items/{id}`）；`:130` 上传；`:192` `repo.update` |
| App（我的发布） | `ershou_app/lib/ui/pages/my_items_page.dart:71` | `offline`；`:85` `relist`；`:128` 已售出不给编辑 |
| App（仓储） | `ershou_app/lib/data/api_repository2.dart:537` | `ItemWriteRepository`：`:563` mine / `:580` update / `:613` offline / `:618` relist / `:623` upload / `:636` publish |
| App（只读仓储） | `ershou_app/lib/data/api_repository.dart:92` / `:117` | `GET /items`（列表）/ `GET /items/{id}`（详情） |

## 4. 已核实结论

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | **`GET /search/hot` 与 `GET /search/suggest` 确实不存在**：`SearchController` 只有 `/status` 与 `/reindex` 两个映射；全仓 Java 里 `suggest` 只命中地图点位的 `POST /trade-points/suggest`；`RedisKeys` 里**没有**热搜 key。功能清单问题 #1 对本模块成立 | `ershou-server/src/main/java/com/ershou/campus/module/search/controller/SearchController.java:32`、`:38`；`ershou-server/src/main/java/com/ershou/campus/module/map/controller/MapController.java:58`；`ershou-server/src/main/java/com/ershou/campus/common/util/RedisKeys.java:16-58`（无 hot/suggest）；`开发文档.md:522` 自述"热搜词（规划中，暂未实现）" |
| 2 | **`item.version` 确实"只加不比"**：`Item` 里只有裸字段 `private Integer version;`（无 `@Version`）；全仓 SQL 里 version **只出现在 SET 子句**（8 处 `version = version + 1`），**没有任何一处出现在 WHERE**；MyBatis-Plus 只注册了分页插件，没有 `OptimisticLockerInnerInterceptor`。并发兜底完全靠 `WHERE status IN (...)` / `= ?` 条件更新 | `ershou-server/src/main/java/com/ershou/campus/module/item/entity/Item.java:50`；`ershou-server/src/main/java/com/ershou/campus/module/item/mapper/ItemMapper.java:17`、`:22`、`:27`、`:42`、`:47`、`:52`、`:63`、`:86`；`ershou-server/src/main/java/com/ershou/campus/common/config/MybatisPlusConfig.java:13-18`；`sql/schema.sql:151`（注释仍写"乐观锁"） |
| 3 | **AI 机审结果没有落 `ai_audit_record`**：全仓 Java **零处**引用该表名/实体；`KeywordContentAuditService.auditText` 的产物只有 `[]` 日志 + 返回值；写库的只有 `item.audit_result` / `item.audit_reason`（发布通过=1，可疑转人工=null，编辑通过=1，违规=2 由运营侧写）。功能清单问题 #4 对本模块成立 | `ershou-server/src/main/java/com/ershou/campus/module/ai/KeywordContentAuditService.java:40`、`:51`（仅 log）；`ershou-server/src/main/java/com/ershou/campus/module/item/service/ItemService.java:329-330`、`:396-401`；`sql/schema.sql:392`（表存在）；grep `ai_audit_record|AiAuditRecord` 于 `ershou-server/src/main/java` → 0 命中 |
| 4 | **`reindexAll()` 是"清空重建"且返回两个数**：先 `indices().delete(index)`，再 `ensureIndex()`（内含建索引 + 分词方案探测），最后按 `id > lastId` 分批 bulk 重放；返回 Map 含 `visible`（`term status=2` 计数）与 `docCount`（全部文档计数），另有 `indexed/analyzer/rebuilt`；水位回拨 `startedAt-60s` | `ershou-server/src/main/java/com/ershou/campus/module/search/service/ItemIndexService.java:277-284`（删索引）、`:285`（重建）、`:290-302`（分批）、`:304`（水位回拨）、`:308-309`（两个计数）、`:312-319`（返回字段）、`:323-338`/`:351-363`（两个 count 实现） |
| 5 | **分页/筛选/排序确实下推到 ES 的 DSL**（不是拉回来再筛）：`status=2` 走 `filter.term`；`categoryId/campusId/sellerId` 走 `filter.term`；`sort` 由服务端拼成 `price asc|desc` 或 `_score desc` 再 `publishedAt desc, id desc`；`from/size` 直接进 DSL 并 `track_total_hits:true` | `ershou-server/src/main/java/com/ershou/campus/module/search/service/ItemSearchService.java:109`（status term）、`:110-118`（三个 term）、`:126-144`（DSL 模板）、`:147-153`（排序）；`ItemService.java:132-158`（只把 id 回库取字段） |
| 6 | **索引名 `ershou_item` 在代码里有两处默认值 + 一处 yml**，ES 端口 9211 **来自 `.env` 环境变量而非代码默认值**：`application-dev.yml` 默认仍是 `9210`，`.env` 的 `ES_PORT=9211` 供 docker-compose 映射（`${ES_PORT:-9210}:9200`），`ES_URIS=http://127.0.0.1:9211` 覆盖后端连接串 | `ershou-server/src/main/java/com/ershou/campus/module/search/config/SearchProperties.java:17`；`ershou-server/src/main/resources/application-dev.yml:25`、`:33`；`docker-compose.yml:82`；`E:\ershou\.env`（`ES_PORT=9211`、`ES_URIS=http://127.0.0.1:9211`） |
| 7 | **发布链路顺序与阈值**：先查 `sys_user` → 注册 7 天内走 Redis 日限额（key `ershou:limit:post:{userId}:{yyyyMMdd}`，TTL 1 天，阈值取 `user.publishLimit`，为空默认 5）→ 机审（BLOCK 抛 `50001`；REVIEW → `status=1`；PASS → `status=2`）→ insert → `afterCommit` 同步 ES + 清缓存 → `status==2` 时登记运营「上架审核」队列 | `ershou-server/src/main/java/com/ershou/campus/module/item/service/ItemService.java:285-298`（限额）、`:301-308`（机审与状态）、`:331`（`expireAt=now+validDays`）、`:341-342`（afterCommit）、`:345-347`（入队）；`ershou-server/src/main/java/com/ershou/campus/common/util/RedisKeys.java:24-26` |
| 8 | **图片上传（`biz=item`）走 MinIO、桶 `ershou` 且启动自动创建 + public-read**；上传前校验 ≤5MB 与 jpg/jpeg/png/webp；统一 Thumbnailator 压成 jpg（长边 >1280 才缩放、质量 0.8）；返回 `publicBaseUrl + "/" + key`（`item/yyyy/MM/{uuid}.jpg`）。`ershou.storage.mode=local` 时切本地实现并靠 `/static/**` 暴露 | `ershou-server/src/main/java/com/ershou/campus/module/file/controller/FileController.java:26-32`；`.../common/storage/MinioFileStorageService.java:30`、`:44-51`、`:83-95`、`:97-101`、`:104-122`；`.../common/storage/LocalFileStorageService.java:20`、`:31-40`；`.../common/config/WebMvcConfig.java:20-21`；`ershou-server/src/main/resources/application.yml:115-125` |
| 9 | **商品有效期 = 7 天（`expire_at`），到期由定时任务置 7 并下架**：`@Scheduled("${ershou.item.expire-cron:0 30 * * * ?}")`，取 `status IN (1,2,3,6)` 且 `expire_at < now()`，逐条**条件更新**（`in(status, EXPIRABLE_ITEM_STATUS)`）避免覆盖并发的下架/重挂，成功后 `closeActiveOrders` 作废挂单，`afterCommit` 同步 ES + 清缓存 | `ershou-server/src/main/java/com/ershou/campus/module/system/task/ScheduledTasks.java:49`（状态集合）、`:127-134`（cron+查询）、`:141-153`（条件更新+作废挂单）、`:155-156`（afterCommit） |
| 10 | **`relist` 语义**：只允许 `status IN (5,7)`；置 `status=2`、`expireAt=now+validDays`、刷新 `publishedAt`（重新曝光）；**不重过机审、不写发布限额 key**；用条件更新 `eq(id).in(status,5,7)` 兜并发；随后 `onItemLiveOptimistic(TYPE_RELIST)` 重新进审核队列。`apply-relist` 只允许 `status=8` → `1` | `ershou-server/src/main/java/com/ershou/campus/module/item/service/ItemService.java:502-540`（relist）、`:555-581`（applyRelist）；`ershou-server/src/main/java/com/ershou/campus/module/item/mapper/ItemMapper.java:63-65`；`ershou-server/src/main/java/com/ershou/campus/module/admin/service/ItemReviewService.java:104-115` |
| 11 | **下架有两条路径且原因要求不同**：卖家路径 `POST /items/{id}/offline` 只校验"本人 + 非已售出"，置 5；运营违规路径 `POST /admin/items/{id}/offline` 用 `@Valid ReasonDTO`（`@NotBlank`）→ 置 8；**审核工作台的「下架」是第三条路**（置 5，原因选填，留空用默认文案）。三条都调用 `closeActiveOrders` 作废挂单 | `ItemService.java:465-486`（卖家）、`:473-475`（已售出拦截）；`.../module/admin/controller/AdminManageController.java:60-63`；`.../module/admin/dto/ReasonDTO.java:9`；`.../module/admin/service/AdminItemService.java:196-206`；`.../module/admin/dto/ReviewOfflineDTO.java:10`；`ItemReviewService.java:254-280`；`ItemMapper.java:52-54`、`:85-88` |
| 12 | **编辑规则**：仅拦 `status==4`（已售出）；`textChanged` 只比较**标题与描述**（图片变化不触发重审）；BLOCK 抛 `50001`、REVIEW → `status=1` 并清 `audit_result`、PASS → 原 `status==1` 时自动上架到 2；提交后按 `textChanged` 决定是否重新进审核队列（改价格/图片不入队） | `ItemService.java:374-379`（属主/已售出）、`:384-387`（textChanged = 标题或描述）、`:390-408`（重审与状态流转）、`:448-454`（updateById + afterCommit + onContentMaybeChanged）；`ItemReviewService.java:123-141`（只在 `status==2` 入队） |
| 13 | **详情浏览量是"Redis 计数 + 定时回写"，不是同步 UPDATE**：`detail` 里 `INCR ershou:item:view:{id}`（首次设 2h TTL 并把 id 加进 `ershou:item:view:dirty`），返回时把未回写增量叠加到 `viewCount`；`flushViewCounts` 每 10 分钟把增量写回 DB（`view_count = view_count + delta`，条件 `delta > 0`）并从 Redis 扣减；列表侧 `applyPendingViews` 用一次 MGET 让首页/搜索与详情数字一致 | `ItemService.java:256-259`（详情叠加）、`:265-281`（计数与 dirty）、`:645-676`（列表 MGET 叠加）；`ScheduledTasks.java:169-222`；`ItemMapper.java:35-36`；`RedisKeys.java:51-58` |
| 14 | **详情缓存防击穿/防穿透机制**：`item:null:{id}` 空值哨兵先于加载判断（命中直接抛 `ITEM_NOT_FOUND`），未命中走 `cache.getOrLoad`（重建锁，只有一个请求回源）；loader 内"库里也没有"时**自己写空值哨兵再抛异常**。`favorited` 与浏览量是**就地改 VO、不回写缓存**（避免把某用户的收藏态写进共享缓存） | `ItemService.java:233-262`（`:237-239` 哨兵、`:241-250` 加载、`:252-255` 注释明确禁止回写） |
| 15 | **ES 不可用/深分页的降级是三层**：① 查询前置 `supports()` 不满足（无关键词 / 带 `preferPointId` / ES 不健康）→ 直接 MySQL；② 深分页 `from+size > 10000` 抛 `NotSuitableForEsException` → **静默回落、不计熔断**；③ 其他异常 `markFailure` → 15s 熔断窗内全部走 MySQL（`fallback-to-db=false` 时改为报 `SYSTEM_ERROR`）。分页大小两端都夹到 100（`Math.min(pageSize,100)` + 插件 `setMaxLimit(100)`） | `ItemService.java:110-129`；`ItemSearchService.java:48-53`、`:66-69`、`:55-57`；`EsGateway.java:26-47`；`SearchProperties.java:20`、`:35`；`MybatisPlusConfig.java:16` |
| 16 | **搜索链路有"回库二次过滤"这一层**（P1-2 的根因）：`searchViaEs` 拿到 ES `total` 后，把不在库/`status != 2` 的 id 逐条跳过，但 `PageResult` 的 `total` 仍用 ES 的 total → **索引与库不同步时会出现 `total > records.size()` 的"幽灵条目"**（代码注释自述历史实测"共 12 件却一件都列不出来"，`reindexAll` 注释记录过 `ES 311 条 vs MySQL 在售 10 条`） | `ItemService.java:138-148`（跳过非 2）、`:157`（total 取 `hits.total()`）；`ItemSearchService.java:105-109`（status 必须同时进 DSL 的注释与实现）；`ItemIndexService.java:261-266`（幽灵文档实测记录） |
| 17 | **两端行为一致的关键点已核对**：Web `PublishListView.canRelist` 与后端 `relist` 的允许集合一致（5/7）；Web/App 的"已售出不可编辑"与后端 `update` 一致；App `ItemWriteRepository.relist/offline/upload/update` 打的接口与后端映射逐一对得上；Web `expireText` 用 `ceil` 避免"刚发就少一天" | `ershou-web/src/views/PublishListView.vue:93-104`、`:109`；`ershou-web/src/utils/format.ts:191-203`；`ershou_app/lib/data/api_repository2.dart:580-633`；`ItemService.java:377-379`、`:511-513` |
| 18 | **本模块全部写路径都会在事务提交后同步 ES 索引并失效商品缓存**（`TxHooks.afterCommit` → `itemIndexService.syncById` + `evictor.itemChanged`）：发布、编辑、卖家下架、relist、applyRelist、卖家过期任务、运营强制下架、审核通过与驳回、审核页下架都在列；另有 `@Scheduled` 每 2 分钟按 `updated_at` 水位增量兜底 | `ItemService.java:341-342`、`:449-450`、`:483-484`、`:527-528`、`:571-572`；`ScheduledTasks.java:155-156`；`AdminItemService.java:209-213`；`ItemIndexService.java:100-124`；`ershou-server/src/main/java/com/ershou/campus/common/util/TxHooks.java:15-26` |

## 5. 发现的问题

> 本轮**未发现 P0**（未发现阻断主流程或造成数据丢失的缺陷）；以下 P1/P2 均来自静态阅读，未实际运行验证（能否复现见第 6 节）。

| # | 级别 | 现象 | 证据 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- |
| 1 | P1 | **`item.version` 是"假乐观锁"**：字段写着"乐观锁"，实际只有 `SET version = version + 1`，没有任何 `WHERE version = ?`，也没有 `OptimisticLockerInnerInterceptor`；`ItemService.update` / `relist` / `expireItems` 的条件更新甚至不改 version | `Item.java:50`（无 `@Version`）；`ItemMapper.java:17/22/27/42/47/52/63/86`（仅 SET）；`MybatisPlusConfig.java:13-18`（只有分页插件）；`ItemService.java:448`（`updateById` 不带版本条件）、`:521-523`（relist 条件只有 status）、`ScheduledTasks.java:145-147`（过期同理）；`sql/schema.sql:151`（注释误导） | 字段名与注释误导后续开发者以为有 CAS 保护；真实并发保护只是 `WHERE status`，对"两个买家同时下单/两个页面同时改价"这类**非状态迁移型冲突**（如两次 `updateById` 改价格、库存）没有任何保护，后写覆盖先写且无人知晓 | 二选一：① 给 `version` 加 `@Version` 并注册 `OptimisticLockerInnerInterceptor`（注意 `lockStock/markSold` 等手写 SQL 需保留 `version=version+1`，且要评估与现有 `WHERE status` 兜底的叠加行为）；② 明确废弃——删字段或把注释改为"状态机版本号，仅用于审计，非并发控制"，并把 `update`/`relist`/`expireItems` 的 version 自增补齐（现在连自增都不一致） |
| 2 | P1 | **搜索结果 total 与 records 可能不一致（幽灵条目）**：`searchViaEs` 用 ES 的 `total` 作为分页总数，却把回库后 `status != 2` 或已删除的 id 跳过 → 用户看到"共 N 件"但列表少几条；极端情况（索引未重建、库里已被 SQL 直删）可能整页空白 | `ItemService.java:138-148`（跳过）、`:157`（total 仍用 `hits.total()`）；`ItemSearchService.java:105-109` 与 `ItemIndexService.java:261-266` 的注释自述历史故障："搜『自行车』显示共 12 件却一条都列不出来"、"ES 311 条 vs MySQL 在售 10 条" | 排序/分页体验受损；用户与运营对"到底有多少件"失去信任；ES 与 MySQL 短暂不同步（重建期间、SQL 直改数据、同步任务延迟 2 分钟）时**必然出现** | ① 短期：`status=2` 已在 DSL 过滤（已做），但**索引文档仍保存全状态**，可在 `reindexAll`/增量同步里对 `status != 2` 的文档改 `delete`（而不是 upsert），让 ES 文档集合 == 可见集合；② 中期：回库过滤后若 `records.size() < hits.ids().size()`，要么把 total 按比例修正，要么把该次请求降级到 MySQL 重查（一次 `count` 成本可接受）；③ 建议补一条集成测试：直接 SQL 删商品后搜索，断言 total 与实际条数一致 |
| 3 | P1 | **AI 机审结果不落 `ai_audit_record`**：表已建好（含 `decision/labels/score/appeal_status/cost_ms`），但 Java 侧零引用；机审只留 `[]` 日志 + `item.audit_result/audit_reason` | `KeywordContentAuditService.java:40`、`:51`（只有 log）；grep `ai_audit_record\|AiAuditRecord` 于 `ershou-server/src/main/java` → 0 命中；`sql/schema.sql:392-408`（表结构完整） | 无法统计误伤率（禁售词误杀）、无法做申诉（`appeal_status` 形同虚设）、无法复算"当时命中了哪个词"（`audit_reason` 会被后续运营驳回覆盖，例如 `ItemMapper.java:47` 把 `audit_reason` 改成运营驳回原因）；问题清单 #4 长期悬空 | 在 `ContentAuditService` 实现或 `ItemService.publish/update` 调用点补写 `ai_audit_record`：`biz_type=item`、`biz_id=itemId`（BLOCK 被拦截时无 itemId，可先落库再抛异常或记 `biz_id=0` + 存文本摘要）、`decision` 由 `AuditResult.decision()` 映射为 1/2/3、`labels` 存命中词列表、`cost_ms` 记耗时；注意**发布被 BLOCK 时事务会回滚**，二选一：把记录写成 `REQUIRES_NEW`，或先在审核里落库再抛业务异常 |
| 4 | P1 | **降级存储（`mode=local`）没有格式/大小校验，也不压缩**：只有"文件非空"一个检查，然后用 `transferTo` 原样落盘并把文件名强制写成 `.jpg` | `LocalFileStorageService.java:26-40`（无 `validate`、无 `compress`、`:33` 固定 `.jpg`）；对照 `MinioFileStorageService.java:83-95`（≤5MB + 扩展名白名单）、`:104-122`（Thumbnailator） | 本地模式下一个改名的 `.txt`/`.exe` 或超大文件会被当成"图片"接收并以 `.jpg` 对外提供（`/static/**` 直接可访问）；与 MinIO 模式行为不一致，容易出现"本地测好好的、换 MinIO 就报错"的分裂 | 把 `validate()`（扩展名 + 大小）与 `compress()`（Thumbnailator 统一转 jpg）抽到共用工具类，两个实现都调；本地实现落盘前先压缩再写，文件名后缀与真实类型一致；顺带讨论"是否允许非图片类型的 `biz`"（当前 `biz` 只用于目录名，`biz=chat/avatar` 也走同一套校验） |
| 5 | P2 | **图片数量三处口径不一**：DB 注释 1–9、后端 DTO `max=9`、**Web 与 App 前端都只给 6** | `sql/schema.sql:134`（"1-9"）；`ItemPublishDTO.java:41`、`ItemUpdateDTO.java:46`（`max=9`）；`ershou-web/src/views/PublishView.vue:187`、`ItemEditDialog.vue:247`（`:limit="6"`）；`ImageUploader.vue:31`（默认 `limit:6`） | 用户最多只能传 6 张（体验缩水），文档/后端却按 9 张描述；两端不一致时容易误判为"后端限制" | 统一为 9（改前端 `:limit="9"` 与 `ImageUploader` 默认值）或统一为 6（改 DB 注释 + DTO `max=6`）；建议在 `功能清单.md` 写明最终口径并加一条前端断言 |
| 6 | P2 | **功能清单 #22 写"改标题/描述/**图片**会重新过内容审核"，代码只对标题+描述重审** | `功能清单.md:353`；`ItemService.java:384-387`（`textChanged` 只比较 title/description）、`:437-440`（图片单独分支，不参与 `textChanged`）、`:452-453`（注释自述"只改价格/库存/图片不算"） | 文档与真实行为不一致；"换一张违规图就绕过机审"是当前设计取舍（图片机审未实装），但文档没写清，容易被当成 bug 或安全漏洞 | 按真实行为修正文档（"只有标题/描述变化才重新过机审；图片机审规划中，见 `ai_audit_record.biz_type=item_image`"）；若确实需要图片过审，需另排期接入图片审核 |
| 7 | P2 | **`ershou.item.valid-days` 与 `ershou.item.expire-cron` 在配置文件中并不存在**，只有代码默认值 | `ItemService.java:95`（`@Value("${ershou.item.valid-days:7}")`）；`ScheduledTasks.java:127`（`@Scheduled(cron = "${ershou.item.expire-cron:0 30 * * * ?}")`）；grep `valid-days\|expire-cron` 于 `ershou-server/src/main/resources` → 0 命中 | 运营/运维"可配"的说法在实际部署里没有任何显式配置项，改值只能靠环境变量或命令行参数；新人找不到配置位置会以为 7 天写死了 | 在 `application.yml` 的 `ershou:` 下补 `item: {valid-days: 7, expire-cron: "0 30 * * * ?"}`（显式优于隐式），并在 `功能清单.md`/`开发文档.md` 指向该位置 |
| 8 | P2 | **ES 端口存在"代码默认 9210 / 实际 9211"的双口径**：`application-dev.yml` 的默认值仍是 9210，9211 完全依赖 `.env` 注入 | `application-dev.yml:25`（`${ES_URIS:http://127.0.0.1:9210}`）；`docker-compose.yml:82`（`${ES_PORT:-9210}:9200`）；`.env`（`ES_PORT=9211` + `ES_URIS=http://127.0.0.1:9211`）；`开发文档.md:438`、`README.md:26` 都写"默认 9211" | **文档与代码默认值不一致**：不复制 `.env` 直接跑（或 CI 环境没有 `.env`）会连到 9210 上被 WSL/QQ 占用的端口，重现"`POST /search/reindex` 报 90001"的老故障，而排查方向会被带偏 | 把 `application-dev.yml` 默认值改成 `9211`（与 `.env`/文档统一），或反过来把日志/`/search/status` 的 `esAvailable` 补上实际连接的 URI 便于一眼定位 |
| 9 | P2 | **禁售词表有重复项**：配置里 23 条中 `仿真枪` 与 `气枪` 各出现两次（去重后 21 条） | `ershou-server/src/main/resources/application.yml:97`（`...,仿真枪,气枪,弩,...,仿真枪,...`）；`KeywordContentAuditService.java:33-37`（逐词 `contains`，重复词会产生重复命中项，`block-keywords` 数量日志会虚高 2） | 命中一次会在 `reason` 里出现两次同一个词（"内容涉嫌违规：仿真枪、仿真枪（禁售品类）"），用户看着像系统出错；词表规模统计不准 | 去重（`split` 后 `distinct()`）或在配置里删掉重复项；建议在 `KeywordContentAuditService` 构造器里对 `split()` 结果 `distinct()` 兜底，避免任何重复配置都产生重复命中 |
| 10 | P2 | **新号发布限额的计数发生在机审之前**：Redis `INCR` 不受事务回滚影响，命中禁售词（`50001`）或 insert 失败的发布**照样吃掉一次当日额度** | `ItemService.java:288-298`（先 `INCR`，`:295-297` 超限才抛）；`:301-305`（机审拦截在其后抛 `BizException`，而 `@Transactional` 只回滚 DB 不撤销 Redis）；`RedisKeys.java:24-26`（key 带日期，TTL 1 天） | 新号第一天被拦几次就发不出商品了，而用户感觉"我一条都没发出去"；这是**计数口径**问题（"尝试次数" vs "成功发布数"），文档写的是"每日限发 5 件" | 明确口径并统一：若按"成功发布数"计，把 `INCR` 移到 `insert` 成功之后（或改用 dirty 计数 + 定时对账）；若坚持"尝试次数"，在 `功能清单.md`/错误文案里写清"包含未通过的发布"，并把超限文案改成不会引起歧义的表述 |
| 11 | P2 | **过期任务与 relist 的状态更新不维护 `version`**，与 Mapper 里其他状态迁移的约定不一致 | `ScheduledTasks.java:141-147`（`updateById` + 条件 update，均无 version）；`ItemService.java:515-523`（relist 无 version）；对照 `ItemMapper.java:17/22/27/42/47/52/63/86`（都 `version = version + 1`） | 即使将来引入真正的乐观锁，这两个路径也会造成"version 不前进"的空档；现在则让"version 到底代表什么"更加含混 | 与 P1-1 的修法一起处理：要么全部补齐自增，要么整体废弃该字段；不要维持"有的路径加、有的路径不加"的中间状态 |
| 12 | P2 | **`update` 用 `updateById` 做部分更新，实际依赖 MyBatis-Plus 的 NOT_NULL 更新策略**（该策略未在配置中显式声明，属框架默认值），且请求体可为 `{}`（空更新） | `ItemService.java:448`（`itemMapper.updateById(update)`）；`application.yml:24-31`（`mybatis-plus.global-config` 只配 `banner`/`id-type`，**没有** `update-strategy`）；`ItemUpdateDTO.java:16-18`（全字段可选，无 `@AssertTrue` 之类的"至少改一项"校验） | 依赖隐式默认值：一旦有人显式配成 `IGNORED` 或升级 MP 版本改变默认策略，`null` 字段会被写成 `NULL`，直接清空价格/标题/描述；空 `{}` 请求会白白走一遍"读商品 + 重审判断 + update + 同步 ES + 入队" | 显式配置 `mybatis-plus.global-config.db-config.update-strategy: not_null`（把隐式约定变成显式）；给 `update` 加"至少提交一个字段"的校验（空请求直接返回或报参数错误），避免无意义写扩散 |
| 13 | P2 | **`preferPointIds`（合并点位一组查询）不参与 ES 判定**：`supports()` 只排除了单点 `preferPointId`，`preferPointIds` 非空但 `preferPointId` 为 null 时**仍会走 ES**，而 `ItemDocument` 里没有点位字段、DSL 里也没有对应 filter → 该筛选条件被静默丢弃 | `ItemSearchService.java:48-53`（只判 `getPreferPointId() == null`）；`ItemService.java:184-191`（`preferPointIds` 只在 `pageFromDb` 里生效）；`ItemDocument.java:15-37`（无 `preferPointId` 字段）；`ItemQueryDTO.java:34`（`List<Long> preferPointIds`） | 地图"合并气泡"小窗如果带上 keyword + `preferPointIds`，ES 分支会忽略点位过滤 → 返回该组之外的商品（**静默错误，不报错**），与注释里明确要避免的那类问题同源 | `supports()` 里并排判断：`query.getPreferPointIds() == null \|\| query.getPreferPointIds().isEmpty()`；或更稳妥地把 `preferPointId` 也写进 `ItemDocument` 并在 DSL 加 `terms` filter；建议补一条测试：`keyword + preferPointIds` 请求断言返回项的 `preferPointId` 都在集合内 |

## 6. 未核实 / 不确定

> 本轮为**纯静态只读核实**：未启动后端/前端/ES，未跑任何 smoke/probe/bench 脚本，未连 MySQL/Redis/ES，因此下列内容**没有运行时证据**，不作为结论：

1. **任何接口的真实运行结果**：`GET /items?keyword=`、`POST /search/reindex?sync=true`、`GET /search/status`、`POST /files/upload`、`POST /items`、`PUT /items/{id}`、下架/relist 的 HTTP 状态码与响应体，均未实际请求；第 4 节只证明"代码这么写"。
2. **ES 实际连通性与分词方案**：当前索引是否存在、`ensureIndex()` 最终落到 `ik_max_word+syn` / `smartcn` / `ngram` 哪一档、`docker/es/custom.dic` 与 `synonyms.txt` 是否被 ES 加载，均未验证（这两个文件本轮也没读）。
3. **P1-2（幽灵条目）能否复现**：代码与注释都指向该风险，但我没有"SQL 直删商品 → 搜索 total 虚高"的实测数据；`reindexAll` 是否已把现有环境修干净也未确认。
4. **P1-4（本地存储缺校验）实际影响面**：未确认当前部署是否用过 `ershou.storage.mode=local`（`application-local.yml` 只覆盖了 `storage.secret-key`，没覆盖 `mode`，所以**默认仍是 minio**——这一点是从配置推断的，未做运行时确认）。
5. **P2-10（限额计数口径）与 P2-12（`{}` 空更新）的实际用户影响**：未跑接口，无法判断线上是否真的发生过；也**未确认** `user.publishLimit` 的实际取值来源与最大值校验（`ItemService.java:294` 只做了 null 兜底，未查该字段是否有别的写入路径或约束）。
6. **两端真机/浏览器行为**：Web 的 `v-html` 高亮在真实搜索结果里的渲染效果、`ItemEditDialog` 的字段级提交是否真的只发改动项、Flutter 端 `publish_page.dart` 编辑模式的字段 diff 是否与后端 `null=不改` 语义完全对齐——都只读到了代码，未运行验证。
7. **`ershou-web` 是否有隐藏的热搜/联想实现**：我在 `ershou-web/src/**` grep `search/hot|/search/suggest|suggest` 只命中地图的 `suggestTradePoint`，但**未逐个通读**搜索相关组件（如未来新增的 hook/组件），因此"前端没有搜索建议下拉"是按现有代码与 HomeView 结构判断的。
8. **管理端**：`AdminItemService.page` 的商品管理筛选与运营强制下架的**页面侧**（`views/admin/*`）不在我的只读范围，未核实 Web 后台是否正确弹"原因必填"；我只核实了后端 `ReasonDTO.@NotBlank`。
9. **测试脚本断言**：`功能清单.md:291` 提到的 `scripts/verify-item-validity.ps1`（25 项）、`verify-close-orders-on-unavailable.ps1` 等**本轮按纪律未运行、也未读**，因此"7 天有效期断言覆盖"的说法我没有独立证据（只是清单里的自述）。
10. **性能数字**：`ItemService.java:217-221` 注释里的"50 并发、P95≈500ms"等历史实测数据我**没有复现**，仅作为代码注释引用，不算我的结论。

## 7. 下一轮计划

> 按纪律，本轮只记录；是否排期由 Leader 决定。以下按"是否改源码"标注。

1. **（需改源码，建议 P1 优先）** 修 P1-2 幽灵条目：`reindexAll`/`syncById` 对 `status != 2` 的文档改删（或统一在增量同步里收敛），并在 `searchViaEs` 中当 `records.size() < ids.size()` 时修正 total 或回落 MySQL；需 Leader 指定唯一写者，写 `ItemIndexService` / `ItemService`。
2. **（需改源码，小改动）** 补 `ai_audit_record` 落库（P1-3）：在机审调用点写 `biz_type=item`、`decision`、命中词、耗时；注意 BLOCK 回滚问题需用 `REQUIRES_NEW` 或先落库后抛异常；表结构已就绪（`sql/schema.sql:392`），无需改 DDL。
3. **（需改源码，决策项）** `version` 字段二选一（P1-1 / P2-11）：要么接 `@Version` + 注册乐观锁插件并补齐所有路径自增，要么删字段/改注释；此决定会影响 `ItemMapper` 全部手写 SQL 与并发行为，**建议 Leader 先拍板口径再动手**。
4. **（需改源码，小改动）** 统一图片口径（P2-5）与本地存储校验/压缩（P1-4）：抽公共 `validate + compress`，前端 `:limit` 与 DTO/DB 注释对齐。
5. **（文档/配置类，不需改代码即可做）** 修 P2-6（功能清单 #22 的"图片重新过审"表述）、P2-7（补 `ershou.item.valid-days` / `expire-cron` 显式配置）、P2-8（`application-dev.yml` 默认端口与文档统一到 9211）、P2-9（词表去重）；另把 P2-10 的限额口径写清。**这些属"证据补全/口径统一"，可由 Leader 统一排一轮。**
6. **（下一轮验证，需起服务）** 由 Leader 在唯一实例上跑一轮：`POST /search/reindex?sync=true` 后比对 `visible` 与 `SELECT COUNT(*) FROM item WHERE status=2`；SQL 直删一条商品后搜关键词验证 total 一致性；发布命中禁售词验证限额是否被消耗；`preferPointIds + keyword` 验证 P2-13 的静默丢条件。**本轮按纪律未执行。**

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-14 12:31 | 收到 | Leader 指派：`task-2` 商品模块第 1 轮**只读核实**，唯一可写文件 `智能体团队/02-item-商品模块.md`，禁止改源码/配置/文档/脚本、禁止起服务与构建；结论必须带真实 `路径:行号`；重点核实问题清单 #1/#2/#4、ES 语义、发布链路、下架/编辑/有效期。 |
| 2026-10-14 12:31 | 提交 | 已 `claim` `task-2`（revision 2）并完成本轮：8 节齐全、18 条已核实结论（均带 `路径:行号`）、13 条分级问题（P0 无 / P1 4 条 / P2 9 条）、第 6 节 10 条未核实、第 7 节 6 条下一轮计划。**未发现 P0**，故未触发"发现 P0 立即先报"的通道。核心发现：① `/search/hot`、`/search/suggest` 全仓不存在（问题 #1 成立）；② `item.version` 只加不比且无乐观锁插件（问题 #2 成立，`ItemMapper.java:17-86` 仅 SET，`MybatisPlusConfig.java:13-18` 无插件）；③ 机审结果不落 `ai_audit_record`（问题 #4 成立，Java 零引用）；④ 搜索 total 可能大于实际 records（幽灵条目，`ItemService.java:138-157`）；⑤ 本地存储降级无校验无压缩（`LocalFileStorageService.java:26-40`）；⑥ 文档"图片重新过审"不成立（`ItemService.java:384-387` 只比标题/描述）。 |
| 2026-10-14 12:31 | 待回 | 等待 Leader 验收（8 节齐全 / ≥3 条带证据 / 问题分级含现象-证据-影响-建议 / 第 6 节显式未核实 / 未改任何源码）。Leader 的验收结论请追加在本表下方。 |
| 2026-10-14 12:52 | 收到 | **Leader 验收结论：通过**（8 节齐全；18 条结论、13 条问题均带证据；第 6 节 10 条未核实显式列出）。抽查复核一致：`ai_audit_record` 在 `ershou-server/src/main/java` 零引用（我独立 grep 确认）；`Item.java:50` 确无 `@Version`。 |
| 2026-10-14 12:52 | 收到 | **裁定 ①** P1-1（`item.version` 老实现在 CAS 上）：**采纳"方案②明确废弃"为主线**——即删字段或把注释改为"状态机版本号、仅审计"，理由：方案①（接 `@Version`）要回归 `ItemMapper` 全部手写 SQL 与 `lockStock/markSold` 的叠加语义，风险/收益不划算。**裁定 ②** P1-2（幽灵条目）与 P1-3（`ai_audit_record` 落库）列入第 2 轮修复候选，P1-3 直接呼应功能清单问题 #4，优先级高于其余 P1。**裁定 ③** P1-4（本地存储降级无校验）与 P2 文档类并入第 2 轮，文档类统一走一轮只改文档的任务。 |
| 2026-10-14 12:52 | 收到 | 第 6 节第 6 条（需起服务验证：reindex 后 `visible` vs MySQL count、SQL 直删后 total 一致性）由 Leader 在唯一实例上统一安排，**不由成员自行起服务**。 |
