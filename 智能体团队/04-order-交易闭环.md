# 交易闭环（收藏 / 购物车 / 面交）· order-member 记录

| 项 | 值 |
| --- | --- |
| 成员 | `order-member` |
| 负责功能 | 收藏、购物车（按卖家分组）、面交预约（含购物车合并面交、只提意向）、面交单管理（接受/拒绝/取消/成交、改地点时间）、信用分 |
| 记录文件 | `智能体团队/04-order-交易闭环.md` |
| 本轮状态 | `已完成` |
| 更新时间 | `2026-10-14` |
| 覆盖功能编号 | 功能清单 #8、#9、#10、#11、#12（以及问题 #3、#10、#12） |
| 共享任务 | `task-4`（只读核实轮，未改任何源码/配置/脚本，未启服务） |

## 1. 本轮状态

- 状态：**已完成**（第 1 轮只读核实）。负责人：`order-member`。更新时间：`2026-10-14`。
- 覆盖 `功能清单.md` 的 **#8 收藏、#9 购物车、#10 面交预约（含合并面交）、#11 面交单管理、#12 信用分**，并逐条核实问题 **#3 / #10 / #12**（结论：三条全部成立，见第 5 节 P2 表）。
- 核实方式：纯静态阅读（后端 Java / Web Vue+TS / 移动端 Dart / `sql/schema.sql` / 功能清单+开发文档），**未启动任何服务、未跑 `scripts/verify-*.ps1`**；行号全部来自本轮真实读到的文件内容。
- 本轮共发现 **P0 × 1、P1 × 4、P2 × 9**；P0 已在写本文件前单独预警 `lead`，`lead` 已复读代码并裁决**采纳为 P0**（主条）。

## 2. 功能边界

- 做什么：
  - **收藏（#8）**：收藏/取消收藏、我的收藏分页、`item.fav_count` 计数与详情缓存失效。
  - **购物车（#9）**：加购即校验（状态/库存/自己的商品）、数量夹取、按卖家分组、改数量、单条移除、结算入口。
  - **面交预约（#10）**：单品直约 + 购物车合并结算（按卖家拆单、共享 `batch_no`）、结算 `ok`/`skipped` 分流、同买家+同商品进行中防重、买家只提意向（服务端忽略 `pointId`/`timeSlot*`）。
  - **面交单管理（#11）**：接受（此刻才锁库）/ 拒绝 / 取消 / 成交、卖家 48h 未确认失效、锁过期释放、改面交地点与时间（**只有卖家**）、终态原因下发、私聊提醒、待办红点。
  - **信用分（#12）**：面交完成双方 +2 并同步重算等级；运营人工调整写 `credit_log`。
- 不做什么：
  - 不做商品发布/编辑/下架本身（`item-member`）、不做地图点位与选点针（`map-member`）、不做私聊消息通道与未读（`chat-member`）、不做运营后台页面（`admin-member`）、不做 ES 重建与移动端基建（`app-member`）。
  - 不改任何源码；本轮只产出结论与问题清单，修复排期由 `lead` 决定。

## 3. 代码地图

### 3.1 后端（`ershou-server/src/main/java/com/ershou/campus/`）

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 购物车 HTTP | `module/cart/controller/CartController.java:20` | `@RequestMapping("/api/v1/cart")` |
| 购物车路由 | `CartController.java:28` GET、`:34` POST、`:43` PATCH `/{cartId}`、`:51` DELETE `/{cartId}`、`:58` POST `/checkout` | **没有** `DELETE /cart` 清空路由 |
| 购物车服务 | `module/cart/service/CartService.java:42` `add`、`:84` `clampQuantity`、`:96` `update`、`:110` `delete`、`:119` `listGrouped`、`:168` `rawByIds`、`:176` `removeByIds` | 加购校验 `:47-59`；插入写 `selected=1` `:72`；按 `sellerId` 聚合 `:132-135` |
| 购物车 VO | `module/cart/vo/CartVO.java:9-26` | 分组字段 + 行字段（**不含 `selected`**） |
| 购物车实体 | `module/cart/entity/CartItem.java:12-22`（`selected` 在 `:20`） | 与 `cart_item` 表一一对应 |
| 收藏 HTTP | `module/cart/controller/FavoriteController.java:15`（`/api/v1/favorites`）、`:22` GET、`:29` POST `/{itemId}`、`:36` DELETE `/{itemId}` | 收藏在 **cart 包**，不在 `module/item` |
| 收藏服务 | `module/cart/service/FavoriteService.java:32` `add`、`:48` `remove`、`:57` `page`、`:73` `isFavorited` | 唯一索引兜并发 `:42-45`；`fav_count` `:38`/`:52` |
| 收藏实体 | `module/cart/entity/Favorite.java:12-18` | `folder_id` 有字段但全库无写入 |
| 面交单 HTTP | `module/order/controller/OrderController.java:21`（`/api/v1/meetup-orders`）、`:28` GET、`:38` `/todo-count`、`:44` POST、`:50` GET `/{id}`、`:56` accept、`:63` reject、`:70` cancel、`:77` PATCH `/{id}/point`、`:83` confirm | 全部 10 个动作 |
| 面交单服务 | `module/order/service/OrderService.java:80` checkout、`:181` createSingle、`:204` doCreate、`:312` page、`:330` todoCount、`:357` accept、`:417` reject、`:442` cancel、`:475` closeActiveOrders、`:506` confirm、`:623` updatePoint、`:701` toVO | 核心状态机 |
| 独立事务留痕 | `module/order/service/OrderStateRecorder.java:27-36` | `REQUIRES_NEW` 置失效，不随主事务回滚 |
| 面交单 DTO | `order/dto/CheckoutDTO.java:17,19,21-23`、`CreateOrderDTO.java:10,14,16-18`、`UpdatePointDTO.java:13-17`、`OrderActionDTO.java:8` | `pointId`/`timeSlot*` 仍在 DTO 里但服务端不读 |
| 面交单实体 | `module/order/entity/MeetupOrder.java:13-42` | 与 `meetup_order` 表一致（**无 `lockExpireAt`**） |
| 商品锁库 SQL | `module/item/mapper/ItemMapper.java:17-19` `lockStock`、`:22-24` `releaseLock`、`:27-29` `markSold`、`:38-39` `addFavCount` | 条件更新是防超卖的唯一闸门 |
| 信用分 SQL | `module/user/mapper/SysUserMapper.java:12-18` `addCredit`（`CASE WHEN` 重算等级）、`:21-22` `setCredit` | 无流水写入 |
| 信用分流水 | `module/admin/entity/CreditLog.java:12-25`、`module/admin/mapper/CreditLogMapper.java:8`、`module/admin/service/AdminUserService.java:109-117` | **唯一**写点，`biz_type="admin"` |
| 定时任务 | `module/system/task/ScheduledTasks.java:58-88` 锁过期释放、`:96-112` 48h 未确认失效、`:127-158` 商品过期下架 | 交易闭环的两个兜底任务 |
| 错误码 | `common/result/ResultCode.java:8` 10001、`:19` 30002、`:21` 30004、`:26` 40002、`:27` 40003、`:29` 40005 | 与功能清单口径一致 |
| Redis key | `common/util/RedisKeys.java:32-33` `itemLock`、`:36-37` `verifyCode` | **两个都无人调用**（死代码） |

### 3.2 Web（`ershou-web/src/`）

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 购物车页 | `views/CartView.vue:15` 本地 `selectedIds`、`:22` `knownIds`、`:25-27` `sellable`、`:49-55` `syncSelection`、`:104-110` 点整行勾选、`:142-153` 结算入口、`:155-161` 结算回执、`:179` 取消全选、`:204-209` 勾选框 `:disabled`、`:234` 移除、`:256` 一键合并面交、`:273-286` `skipped` 提示 | 选中态纯本地，**不走 `selected` 字段** |
| 收藏页 | `views/FavoritesView.vue:16-28` 拉取、`:35-37` 取消收藏后重载、`:55` `ItemCard initial-favorited` | `fetchFavorites` 来自 `api/item.ts` |
| 面交单页 | `views/OrdersView.vue:22-25` 状态字典、`:43-50` 状态判定、`:58-65` 终态原因、`:108-110` 接受、`:112-126` 拒绝、`:128-142` 取消、`:145-152` 已卖出/我收到了、`:173-198` tab 角标、`:205-210` 状态标签、`:237-240` 原因条、`:248-271` 买家动作、`:274-290` 卖家动作 | **无「改面交地点」入口**（在 `ChatView` 交易卡片） |
| 预约弹窗 | `components/MeetupBookingDialog.vue:52-57` 模式判定、`:59-82` 提交、`:61-66` payload（`pointId/timeSlot*` 全 `null`）、`:96-102` 买家不填说明 | 单品/合并共用 |
| 购物车 store | `stores/cart.ts:16-43`（`:29-32` 件数之和口径） | 只被 `ItemDetailView.vue:108` 与 `DefaultLayout.vue:59` 调用 |
| 面交 store | `stores/meetup.ts:20-34` `loadTodo`、`:37-41` `startPolling(30s)` | 红点数据源 `GET /meetup-orders/todo-count` |
| 面交时间工具 | `utils/meetup.ts:42-46` 默认窗、`:64-81` 校验、`:84-92` 禁用日期 | 买家侧已不再使用（弹窗注释 `MeetupBookingDialog.vue:7`） |
| API 层 | `api/cart.ts:69-87`（`:81-83` 只删单条，**无清空**）、`api/order.ts:101-103` 直约、`:116-118` 改地点、`:121-133` 三动作、`:139-141` confirm | |
| 私聊交易卡 | `views/ChatView.vue:59` `isSeller`、`:75` 改地点只传 `pointId`、`:659-672` 卖家才有的改地点按钮 | 买家入口被 `v-if` 藏掉 |
| 详情页按钮 | `views/ItemDetailView.vue:234-244` 文案/置灰、`:247-260` 查我有没有进行中的单、`:313-317` 挂载后查一次 | 未传 `status` 过滤 |
| 布局红点 | `layouts/DefaultLayout.vue:43-53` `activeMenu`、`:102` 挂载时算购物车数、`:108` 面交轮询、`:112-118` 路由变化只刷面交、`:166`/`:175` 角标条件 | 购物车角标**不随路由/移除刷新** |
| 状态字典 | `utils/format.ts:102-107` `ORDER_STATUS`（4=已取消、5=已失效） | 与后端 `statusText` 一致 |

### 3.3 移动端（`ershou_app/lib/`）

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 购物车页 | `ui/pages/cart_page.dart:32` 本地 `_selected`、`:34` `_known`、`:71-81` `_syncSelection`、`:99-107` 点行勾选、`:110-121` 改数量、`:123-144` 移除、`:147-165` 合并面交（先选点位）、`:167-192` 提交（`:170-173` 传 `pointId`）、`:194-223` `skipped` 回执、`:375` 移除按钮、`:429-488` 底部栏 | 无「清空购物车」 |
| 面交单页 | `ui/pages/meetup_orders_page.dart:39` 拉一页、`:116-141` 双 tab（无数字）、`:143-231` 卡片、`:206-224` 终态原因、`:250-273` 改地点、`:277-338` 选时间档、`:341-401` 动作区（`:345` 卖家才有的改地点） | **无「取消」按钮** |
| 收藏页 | `ui/pages/favorites_page.dart:29-48` 拉取、`:50-61` 取消收藏、`:76-100` 列表 | |
| 详情页按钮 | `ui/pages/item_detail_page.dart:73-74` 挂载后查进行中单、`:222-248` 加购+红点刷新、`:736-749` 纯函数 `bookButtonState` | 置灰逻辑两端同口径 |
| 详情页取单 | `data/api_repository2.dart:404-414` `activeOrderOfItem`（role×status 精确查）、`:324-347` `page`、`:350-363` 直约（`:360` 传 `pointId`）、`:382-402` 改地点、`:427-430` `todoCount`、`:476-479` 删单条、`:485-499` 结算（`:493` 传 `pointId`） | |
| 红点 | `ui/pages/profile_page.dart:34` 一次拉取、`:60-61` 「我的面交」角标、`:211-212` 待办格；`ui/pages/shell_page.dart:100` 底部导航只有「消息」角标 | 无轮询、无导航角标 |

### 3.4 数据库（`sql/schema.sql`）

| 表 | 位置 | 关键字段/索引 |
| --- | --- | --- |
| `favorite` | `schema.sql:177-186` | `folder_id:181`、唯一键 `uk_fav_user_item(user_id,item_id):184`、`idx_fav_item:185` |
| `cart_item` | `schema.sql:188-201` | 冗余 `seller_id:192`、`quantity:193`、`selected:195`、唯一键 `uk_cart_user_item:199`、`idx_cart_user_seller:200` |
| `meetup_order` | `schema.sql:206-242` | `order_no:208`、`batch_no:209`、`point_snapshot:218`、`time_slot_start/end:219-220`、`status:221`、`reschedule_count:228`、`cancel_reason:230`、索引 `:236-241`（**无 `lock_expire_at` 列**） |
| `credit_log` | `schema.sql:314-327` | `change_value:317`、`before/after_score:318-319`、`reason:320`、`biz_type:321`、`biz_id:322`、`operator_id:323`、`idx_credit_user:326` |
| 锁库字段 | `schema.sql:142-143`、索引 `:158` | `item.lock_user_id/lock_expire_at` + `idx_item_lock(status, lock_expire_at)`（`:143` 注释仍写「30分钟」，已过期） |

## 4. 已核实结论

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | 加购即按结算口径校验：已售出 30002、已锁定 30004、非在售 10001、自己的商品 10001 | `CartService.java:47-59`；错误码 `ResultCode.java:19,21,8` |
| 2 | 数量夹取：孤品恒 1；`quantity<1` 归 1；超库存直接报「库存只剩 N 件」（不是静默夹到库存） | `CartService.java:84-94`；`update` 复用同函数 `:106` |
| 3 | 同一 `user_id+item_id` 只能有一条购物车记录：先查后插，DB 唯一键兜底 | `CartService.java:61-73`；`schema.sql:199` |
| 4 | 购物车按 `seller_id` 聚合，商品快照（标题/封面/价格/状态文案/点位）批量拼装 | `CartService.java:120-166` |
| 5 | 买家的 `pointId`/`timeSlot*` 服务端**确实被忽略**：结算按「每件商品自己的 `preferPointId`」取点、时间传 `null`；单品直约同样忽略 | `OrderService.java:80-82,120-122`（结算）、`:175-177` `buyerSidePoint`、`:182-188`（直约，注释明说"不调 validateSlot"）；DTO 里字段仍存在 `CheckoutDTO.java:19,21-23` |
| 6 | 合并结算分流真实存在：逐条 `unavailableReason` → `ok`/`skipped`，`skipped` 带 `cartId/itemId/title/reason` 返回；**全部不可约才抛 30004** | `OrderService.java:94-107`、`:108-111`、`:131-135`；前端展示 `CartView.vue:273-286`、`cart_page.dart:205-215` |
| 7 | 同买家+同商品同时只能有一张进行中单（1/2），重复提交 40003；单品与合并结算都走 `doCreate` | `OrderService.java:226-233`；`ResultCode.java:27` |
| 8 | 提交意向**不锁库**；卖家 `accept` 才用条件更新锁库：`UPDATE item SET status=3 ... WHERE id=? AND status=2`，锁到「时间窗结束+2h」，无时间窗 24h，过期时间早于当前则给 2h 缓冲 | `OrderService.java:204-260`（建单不动物品）、`:366-374`、`ItemMapper.java:17-19` |
| 9 | 锁库失败（影响 0 行）→ 抛 30004，并把这单置 5 已失效（独立事务，不随主事务回滚） | `OrderService.java:374-379`；`OrderStateRecorder.java:27-36` |
| 10 | 接受时把该商品上其他「待确认(1)」意向批量置 5（原因「卖家已把商品确认给其他同学」）并逐单私聊 | `OrderService.java:391-403`、`:411-414` |
| 11 | 解锁定时任务：每分钟扫 `status=3 && lock_expire_at<now` → 释放回在售，并把该商品上 `status=2` 的单置 5「面交时间已过…自动失效」 | `ScheduledTasks.java:58-88` |
| 12 | 48h 未确认失效任务：每 5 分钟扫 `status=1 && created_at < now-48h` → 置 5 | `ScheduledTasks.java:96-112` |
| 13 | 改地点/时间**只有卖家能改**（校验 `sellerId` → 40002），状态限 1/2，必须传 `pointId`；跨校区点位被拒 | `OrderService.java:628-630`、`:631-633`、`:634-636`、`:642-646` |
| 14 | 只传地点不会清掉已有时间：`changeSlot` 仅在时间字段有文本时才为 true，且 `validateSlot` 规则=两边都空=待协商、单次≤180 分钟、最远 7 天、不早于当前 | `OrderService.java:647-650`、`:564-593`；常量 `:74` |
| 15 | 改完给对方发私聊（事务提交后执行，best-effort），`reschedule_count + 1` | `OrderService.java:660-672`、`:271-278` |
| 16 | 成交由卖家一次点击完成：商品 `markSold` + 双方信用分 +2，同时作废该商品上其他进行中单；买家 `confirm` 只写 `buyer_confirm_at` | `OrderService.java:506-548`（`:534` markSold、`:536` 收单、`:539-540` +2）、`:514-524` |
| 17 | 信用分加分用 `CASE WHEN` 先在一条 UPDATE 里重算 `credit_level` 再改分（规避 MySQL 从左到右取值） | `SysUserMapper.java:12-18` |
| 18 | **问题 #3 成立**：`credit_log` 唯一写点是运营人工调整；成交自动 +2 **不写任何流水** | 写点：`AdminUserService.java:107-117`；加分：`OrderService.java:539-540`（无 `CreditLog` 调用）；全仓 `credit_log|CreditLog` 仅 12 处命中，业务侧只有 admin |
| 19 | **问题 #10 成立**：没有任何「清空购物车」接口。实际路由全集 = `GET /api/v1/cart`、`POST /api/v1/cart`、`PATCH /api/v1/cart/{cartId}`、`DELETE /api/v1/cart/{cartId}`、`POST /api/v1/cart/checkout` | `CartController.java:20,28,34,43,51,58`；Web `api/cart.ts:69-87` 无清空函数；移动端只有 `api_repository2.dart:476-479` 单条 DELETE |
| 20 | **问题 #12 成立**：`cart_item.selected` 后端**只写不读**——全仓唯一写点 `CartService.java:72`，`getSelected` 零命中；`CartVO.Row` 也没有该字段；两端选中态都是页面本地状态 | 写点：`CartService.java:72`（`ci.setSelected(1)`）；实体 `CartItem.java:20`；VO `CartVO.java:15-26`（无 selected）；前端 `CartView.vue:15,49-55`、`cart_page.dart:32,71-81` |
| 21 | 收藏防重复：唯一索引兜底 + `DuplicateKeyException` 静默忽略；取消收藏只在删除成功时扣 `fav_count`（`GREATEST(...,0)` 防负）；收藏数变化清详情缓存 | `FavoriteService.java:32-46`、`:48-55`；`ItemMapper.java:38-39`；`schema.sql:184` |
| 22 | 我的收藏分页上限 100，且过滤掉取不到商品的记录，`favorited=true` 全量置位 | `FavoriteService.java:58`、`:64-70` |
| 23 | 待办红点口径：卖家=待确认(1)；买家=已接受(2)且 `buyer_confirm_at` 为空；返回 `asSeller/asBuyer/total` | `OrderService.java:330-343`；前端 `stores/meetup.ts:27-31`、`OrdersView.vue:178-196` |
| 24 | Web 红点：面交 30 秒轮询 + 动作后即时刷新；已在该页时导航角标收起；购物车角标口径=件数之和 | `DefaultLayout.vue:108`、`:112-118`、`:166,175`；`OrdersView.vue:100`；`stores/meetup.ts:37-41`；`stores/cart.ts:29-32` |
| 25 | 移动端「约过就灰」真实生效：纯函数 + 挂载后按 role/status 精确查（`pageSize=5`，双 role×状态） | `item_detail_page.dart:73-74,736-749`；`api_repository2.dart:404-414`；Web 同口径 `ItemDetailView.vue:234-244` |
| 26 | 已售出不可再改：商品侧编辑只拦 `status=4`；面交单侧改地点/时间仅限 1/2 | `ItemService.java:377-379`；`OrderService.java:631-633` |
| 27 | 「商品不可交易 ⇒ 挂单全作废」的四个触发点都接上了：卖家下架、运营下架（审核台/管理台）、成交、商品过期 | `ItemService.java:482`、`ItemReviewService.java:268`、`AdminItemService.java:202`、`OrderService.java:536`、`ScheduledTasks.java:152` |
| 28 | 终态原因随 `OrderVO.cancelReason` 下发，两端都用红底条摊开显示 | `OrderService.java:717`；`OrdersView.vue:237-240`；`meetup_orders_page.dart:206-224` |
| 29 | 数据库与实体一致：`cart_item`/`favorite`/`meetup_order`/`credit_log` 四张表字段与实体逐项对得上 | `schema.sql:177-201,206-242,314-327` ↔ `CartItem.java:12-22`、`Favorite.java:12-18`、`MeetupOrder.java:13-42`、`CreditLog.java:12-25` |
| 30 | 核销码确实已删干净：`verify_code/verify_expire_at` 无任何写入，`verifiedAt` 只读不写（前端拿到的永远是 null） | `MeetupOrder.java:30-32`；`OrderService.java:719`；`AdminViewAssembler.java:214`；无 `setVerifyCode/setVerifiedAt` 命中 |

## 5. 发现的问题

### P0

| # | 级别 | 现象 | 证据 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- |
| P0-1 | **P0** | 卖家「接受」后才锁库，但**之后卖家改/补面交时间不会更新锁的到期时间**；锁固定为「接受时刻+24h」（因为接受接口收不到时间窗，买家提交时又不填时间），一旦卖家把面交时间定到 24h 之外，每分钟的解锁任务就会把商品释放回在售，并把这张**已确认**的待面交单置为「已失效」，买家侧**没有任何私聊提醒**。触发条件 = 「卖家接受后再定时间，且时间在 24h 之外」——正常操作即可，无需恶意。**状态：纯静态代码推断，本轮未运行时复现（未起服务、未造单）。** | 证据链按触发顺序：① 锁只写一次，值为「时间窗结束+2h」，无时间窗则 `now+24h`：`OrderService.java:366-374`（`:374` `itemMapper.lockStock(...)`），SQL `ItemMapper.java:17-19`；② `accept` 接口不收时间窗参数：`OrderController.java:56-60`；买家侧一律传 null：`OrderService.java:121-122`（结算）、`:187-188`（直约）、`:175-177`（点位/时间来源）；③ 之后改时间只写 `time_slot_*`，**不碰** `item.lock_expire_at`：`OrderService.java:647-661`（全类 `lockStock(` 仅 `:374` 一处命中）；④ 解锁任务到点释放并置 5：`ScheduledTasks.java:58-88`（`:72` releaseLock、`:79-83` 置 5 已失效）；⑤ 允许预约到 7 天后：`OrderService.java:590` | 已确认的面交单被系统自动作废；商品回到在售可被第二个买家预约并成交 → **同一件商品二次售出**；双方都以为单子还在，卖家可能已经不在原时间到点，买家到点白跑；数据上留下「已失效」单（原因文案还与事实不符："面交时间已过"）。 | 二选一（建议都做）：<br>① `updatePoint` 在改时间后**同步重算并条件续锁**：目标到期时间 = 新 `time_slot_end+2h`（仍为空则 24h），用条件更新 `UPDATE item SET lock_expire_at=?(, lock_user_id=?) WHERE id=? AND status=3 AND lock_user_id=?`，影响 0 行则明确报错（说明锁已被释放/商品已不可锁）；<br>② 或在 `accept` 时要求/允许一并提交时间窗，并让解锁任务在释放前先校验该商品是否仍有 `status=2` 的单子且其 `time_slot_end` 未过，避免"有活跃单却解锁"。<br>附带：解锁作废单时补一条私聊（见 P2-1）。 |

### P1

| # | 级别 | 现象 | 证据 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- |
| P1-1 | P1 | 成交时 `markSold` 的返回值**未校验**：`itemMapper.markSold` 是条件更新（仅 `status IN (2,3)` 生效），若此时商品已不在 2/3，则 0 行更新且**不报错**，单子照样被置为已完成并给双方 +2。**升级条件**：仅当已锁定(status=3)的商品被卖家编辑标题/描述且机审转人工（`ItemService.java:397-407` 把商品置为 status=1）时才会走到 0 行；UI 上该路径可达——`ItemDetailView.vue:147-148` 只排除已售出(4)，`ItemEditDialog.vue:77-79` 的 `UNEDITABLE` 也只声明 4。 | 原始代码：`OrderService.java:534` `itemMapper.markSold(order.getItemId());`（返回值丢弃，下一行直接 `closeActiveOrders`）；SQL：`ItemMapper.java:27-29` `... WHERE id=#{itemId} AND status IN (2,3)`；编辑只拦 4：`ItemService.java:377-379`；机审 REVIEW 置 1：`ItemService.java:397-399`、`:407`；UI 可达：`ItemDetailView.vue:147-148`、`ItemEditDialog.vue:73-81` | 交易被记成「已完成」、信用分已加，但商品没被置为已售出：商品若被审核放行回到在售，可被别人再买（双卖）；买家订单与商品状态互相矛盾；日志只有一句"交易完成"，无异常无告警。 | `markSold` 结果必须校验：`int sold = itemMapper.markSold(id); if (sold == 0) throw new BizException(ResultCode.ITEM_STATUS_ERROR, ...)`（或在同一事务里先条件更新商品、失败即回滚整单）；另外建议 `ItemService.update` 在 `status==3` 时禁止改标题/描述（或改后不改变 status、只登记待复审），从源头消掉"已锁定商品被改回审核中"。 |
| P1-2 | P1 | 合并结算的 `ok`/`skipped` 分流**没覆盖「同买家+同商品已有进行中单」这一条**：分流只调 `unavailableReason`（状态/自己商品），而 40003 校验在后面的 `doCreate` 里抛异常 → 购物车里只要有一件"我已经约过"的商品，**整批结算回滚**，其他可约商品也一起失败。 | 分流只查状态：`OrderService.java:94-107`、`unavailableReason` `:139-156`（无进行中单判断）；循环里 `doCreate` 抛 40003：`:121-123` + `:226-233`；抛异常导致整批回滚（`@Transactional` `:79`），`removeByIds` 也不会执行 `:128` | 违背功能清单 #10 承诺的"坏条目不影响整批"（功能清单.md:163）；用户看到 40003 且一件都没约成，只能自己找出是哪件重复。 | 把"已有进行中单"也纳入 `unavailableReason`（查一次该买家在这批货上的进行中单集合），变成 `skipped` 条目并在前端列出；或对 40003 单独 catch 后转 `skipped` 继续循环。 |
| P1-3 | P1 | 移动端「我的面交」**没有「取消」按钮**：买卖两侧都只能接受/拒绝/我已收到/已卖出，买家/卖家都无法在 App 里取消一张还挂着的单（后端 `cancel` 对双方开放，Web 有入口）。 | 动作区 `meetup_orders_page.dart:341-401`（无任何 cancel 调用，全文件无 `cancel` 命中）；Web 有：`OrdersView.vue:128-142,248-256`；API 存在：`OrderController.java:70-74`、`api_repository2.dart` 无 cancel 方法 | 移动端用户想取消约面交只能转到 Web，或干脆失约（还会让商品一直挂着等 48h 失效）；体验与两端一致性都受损。 | 在移动端动作区补「取消」（状态 1/2，双方可见），复用 `POST /meetup-orders/{id}/cancel`，先补 `OrderRepository.cancel`。 |
| P1-4 | P1 | 库存语义没闭环：<br>① 发布/编辑允许 `is_unique=0` 且 `stock>1`（Web 发布页可填到 999），购物车也按 `[1,库存]` 夹取；<br>② 但成交一律 `markSold` → `status=4, stock=0`，**一次成交即整件售出**，剩余库存作废；<br>③ 单品直约 `doCreate` 的 `quantity` **只做 `<1` 兜底，不夹取库存也不校验孤品**（购物车路径才夹取）；<br>④ 面交单 `deal_price` 是单价，列表按「¥单价 × 数量」展示但不计算总额。 | ① `PublishView.vue:139`（`stock: form.isUnique ? 1 : form.stock`）、`:296-298`（可填 1-999）、`ItemService.java:321-322`（默认值，无约束）；② `ItemMapper.java:27-29`、`OrderService.java:534`；③ `OrderService.java:247`（`quantity==null||<1?1:quantity`）对比购物车 `CartService.java:84-94`；④ `OrderService.java:248`、`OrdersView.vue:219` | 多库存商品的剩余数量在首次成交后静默清零；通过直连接口可给孤品下 `quantity=99` 的单，订单数量与实际不符（数量还会进列表与统计口径）。 | 明确产品口径后二选一：<br>① 若不支持多库存 → 发布/编辑强制 `stock=1`，`clampQuantity` 简化为恒 1；<br>② 若支持 → 成交时按 `order.quantity` 扣减 `stock`，只有 `stock=0` 才 `markSold`；并把直约的 `quantity` 也走 `clampQuantity`。 |

### P2

| # | 级别 | 现象 | 证据 | 影响 | 建议修法 |
| --- | --- | --- | --- | --- | --- |
| P2-1 | P2 | 定时任务自动作废单子**不发私聊**：锁过期任务把 `status=2` 的单置 5「面交时间已过…自动失效」、48h 任务把 `status=1` 的单置 5，两处都只写库，不通知对方；`ScheduledTasks` 整个类没有 `ChatService` 依赖。 | `ScheduledTasks.java:78-84`、`:99-108`；类字段 `:34-43`（无 `chatService`）；对比 `OrderService.java:271-278` 的 `notifyPeer` 与 `:492-493` 的作废通知 | 与功能清单 #10/#11「提醒（全程私聊）」的承诺不符（功能清单.md:170,191）；用户侧只在列表里看到状态变化，尤其 P0-1 场景下买家完全不知情。 | 两个任务改为复用 `OrderService.closeActiveOrders(itemId, reason)`（它已带逐单私聊），或注入 `ChatService` 补 `notifyPeer`；同时把 P0-1 的续锁修好，减少这类作废。 |
| P2-2 | P2 | 移动端「合并面交」仍让买家**选面交点**并把 `pointId` 上传，而服务端已按产品规则**忽略**该参数（改用商品自己的 `prefer_point_id`）→ 用户以为是自己定的点，实际被丢弃（成功回执里的点位来自商品）。 | 移动端选点+传参：`cart_page.dart:147-165,167-173`、`api_repository2.dart:485-499`（`:493` `pointId`）；直约也传：`api_repository2.dart:350-363`（`:360`）；服务端忽略：`OrderService.java:80-82,120-122,175-177` | 跨端行为不一致（Web 弹窗已明确"由卖家确定"：`MeetupBookingDialog.vue:96-102`）；买家白选一次点位，沟通成本反而升高。 | 移动端去掉选点步骤（或改为只读提示"由卖家确定"），与 Web 弹窗、后端规则三处对齐；`CreateOrderDTO/CheckoutDTO` 的 `pointId/timeSlot*` 建议直接删字段或加 `@Deprecated` 注释。 |
| P2-3 | P2 | 「同买家+同商品只能有一张进行中单」是**先查后插**，没有 DB 唯一约束兜底，也没有对该组合加锁；并发双提交存在窗口。 | 校验：`OrderService.java:226-233`（selectCount 后 insert `:256`）；`meetup_order` 唯一键只有 `uk_order_no`，索引 `:236-241` 无 `(buyer_id,item_id,status)` 唯一约束 | 极端并发（连点/重放）下仍可能出现两张进行中单，正是功能清单声称"绕不过去"的那条。 | 加生成列或应用层分布式锁；最简做法：`UNIQUE KEY uk_active (buyer_id, item_id, active_flag)`（`active_flag` 仅在 1/2 时有值，终态置 NULL）。 |
| P2-4 | P2 | Web 购物车角标在**移除/结算后不刷新**：`stores/cart.ts` 只在商品详情加购（`ItemDetailView.vue:108`）和布局挂载（`DefaultLayout.vue:102`）被调用，`CartView.vue` 根本没有引入 cart store；路由变化只刷面交。 | `CartView.vue:1-16`（导入里无 cart store）、`:123-161`（移除/结算后只 `loadCart()`）；`DefaultLayout.vue:112-118`（只 `refreshMeetupTodo`）；功能清单.md:152 声称"加购 / 移除 / 结算后都 refresh" | 顶栏购物车件数停留在旧值，用户以为没删掉/没结算；与文档承诺不符。 | `CartView` 的移除/改数量/结算回执后调用 `cartStore.refresh()`；或把路由 watcher 扩展为进入/离开 `/cart` 时刷新。 |
| P2-5 | P2 | Web 详情页判断"我约过没有"时**没带 `status` 过滤**（后端支持）且只取一页 10 条，历史单多的商品会把进行中的单挤出一页 → 按钮不置灰、可再次点击（随后被后端 40003 拦）。移动端是对的（role×status 精确查）。 | Web：`ItemDetailView.vue:254-255`（`fetchMeetupOrders({role:'buyer',itemId,pageNum:1,pageSize:10})` 后内存 find 1/2）；后端支持：`OrderService.java:318-319`；移动端对照：`api_repository2.dart:408-414` | 同一商品两条进行中单的入口体验不一致；用户点了才吃 40003。 | Web 改为 `status` 分两次（1 和 2）或用后端 `status` 过滤，与移动端同口径。 |
| P2-6 | P2 | 收藏不校验商品是否存在/可收藏：`FavoriteService.add` 直接 insert 再 `addFavCount`（对不存在的商品影响 0 行、不报错），列表页又把这些取不到商品的记录过滤掉 → DB 留下孤儿收藏行。 | `FavoriteService.java:32-46`（无 `itemMapper.selectById` 校验）、`:38`、`:64-66`（过滤 `null`） | 数据脏但不可见；`fav_count` 不会脏（`GREATEST` 兜底），但用户重复收藏已下架/已删除商品时行为不明确。 | `add` 先校验商品存在与可收藏状态（或至少存在），不存在则报 30001；历史孤儿行加一次性清理脚本。 |
| P2-7 | P2 | 死代码/过期字段：`RedisKeys.itemLock`（锁库 key）与 `RedisKeys.verifyCode` 全仓无人调用；`MeetupOrder.verifyCode/verifyExpireAt/verifiedAt` 无写入（`verifiedAt` 永远 null 还下发到 VO）。 | 键定义 `RedisKeys.java:32-33,36-37`（`item:lock|itemLock|ItemLock` 仅命中这两行）；实体 `MeetupOrder.java:30-32`；只读不写：`OrderService.java:719`、`AdminViewAssembler.java:214`、`OrderVO.java:39` | 与功能清单.md:175「只保留锁库 key `ershou:item:lock:{itemId}`」和开发文档.md:507 的描述不符（实际锁只在 DB）；新人误以为还有 Redis 锁。 | 删除两个未用 key 与三个核销残留字段（或注释标记 `@Deprecated` + 说明"锁只在 DB"），同步更新文档。 |
| P2-8 | P2 | 文档/注释与代码不一致（状态机命名 + 权限 + 流水 + 锁字段归属）：<br>① 状态常量语义与 `statusText` 相反（`STATUS_REJECTED=4` 实际是"已取消"，`STATUS_CANCELLED=5` 实际是"已失效"），`OrderService.java:62` 的 javadoc 写"4 已拒绝 / 5 已取消"；<br>② 多处仍写"改面交地点买卖双方都能改"；<br>③ 开发文档写"信用分 +2 且 credit_log 有流水"；<br>④ 功能清单说 `meetup_order` 有 `lock_expire_at`（实际在 `item`）；<br>⑤ `schema.sql` 的 `lock_expire_at` 注释还写"30分钟"。 | ① `OrderService.java:62` vs `:66-67`、`:428`、`:450`、`:743-748`，`schema.sql:221`，`meetup_orders_page.dart:340`；② `OrderController.java:76`、`OrderService.java:615`、`UpdatePointDTO.java:6`、`api/order.ts:105`、`ChatView.vue:61`、`开发文档.md:1338`（真实行为在 `OrderService.java:628-630`、`ChatView.vue:663`、`meetup_orders_page.dart:345`）；③ `开发文档.md:1698` vs 结论 #18；④ `功能清单.md:159` vs `schema.sql:206-242`、`MeetupOrder.java:13-42`；⑤ `schema.sql:143` vs `OrderService.java:71-73` | 命名反直觉极易在下一轮改代码时改错（例如"取消"改成状态 5 会变成"已失效"）；文档承诺与实现不符。 | 统一按 `statusText` 口径重命名常量（`STATUS_CANCELLED=4/STATUS_INVALID=5`），批量订正上述注释与两处文档；`schema.sql:143` 注释改为"锁到时间窗结束+2h（无时间窗 24h）"。 |
| P2-9 | P2 | 移动端待办红点与文档承诺不符：只有「我的」页在进入时算一次（无轮询），底部导航没有「我的面交」角标，面交页两个 tab 也没有数字；Web 侧是 30 秒轮询 + 页内 tab 数字。 | 移动端：`profile_page.dart:34,60-61,211-212`（initState 一次，无 timer）、`meetup_orders_page.dart:116-141`（tab 仅文字）、`shell_page.dart:100`（底部导航只有「消息」badge）；后端口径 `OrderService.java:330-343`；Web 对照 `DefaultLayout.vue:108`、`OrdersView.vue:178-196`；功能清单.md:193 | App 用户不主动进「我的」页就看不到"有人约我"；对方接受/拒绝也不会亮。 | 移动端把 `todoCount()` 提到 shell/面交页的定时刷新（或复用消息侧已有的轮询机制），并给「我的面交」入口与两个 tab 加数字。 |

## 6. 未核实 / 不确定

- **所有问题的运行时复现都没做**（本轮纪律：不起服务、不跑 smoke/probe）：P0-1、P1-1~P1-4 均为**静态代码推断**，未用 Running 后端 + 造单复现；P0-1 的复现步骤建议为「买家约面交（不填时间）→ 卖家 accept → 卖家 PATCH 时间到 24h 之外 → 等到锁到期（或临时把 `lock_expire_at` 改到过去）→ 观察单子是否被置 5 且商品回到 2」。
- 未核实**机审 REVIEW 的实际命中率**（决定 P1-1 的可达频率）：`AuditService`/`ai` 模块的判定阈值与转人工比例未读未测；`ItemReviewService.onContentMaybeChanged` 在 `status!=2` 时提前返回（`ItemReviewService.java:123-129`），status=3 被改成 1 的商品是否会缺 `item_review` 队列记录（`backfillMissing` 触发时机 `:548`，`pendingAuditCount` 只数 `item.status=1` `:351-352`）**需与 `item-member`/`admin-member` 交叉核实**。
- 未核实**库里现存数据**是否有已被"锁过期任务"误作废的 `meetup_order`（需连库查询：`status=5 AND cancel_reason LIKE '%面交时间已过%'` 且 `time_slot_end > updated_at`），也未核实 Redis 里是否残留历史 `ershou:item:lock:*` key。
- 未核实 **Sa-Token 登录拦截配置**（哪些 `/api/v1/cart`、`/api/v1/meetup-orders` 路由需要登录、未登录返回什么错误码）：未读 `SaTokenConfig`/拦截器；`StpUtil.getLoginIdAsLong()` 的未登录行为仅为常识推断。
- 未核实 **ES 索引与缓存的最终一致性**：订单状态流转后的 `itemIndexService.syncById` / `evictor.itemChanged` 是否真的落库可见（未跑），以及 `item.lock_expire_at` 改动是否需要同步索引（若按 P0-1 修法改动锁时间，可能与 ES 的 `updated_at` 增量同步有交互）。
- 未核实 `utils/meetup.ts` 的 `defaultMeetupRange/validateMeetupRange` 现在还有哪些调用方（本轮只确认预约弹窗不再用；未全仓 grep 调用点），是否已成死代码待下一轮确认。
- 未核实**移动端发布页**是否也允许 `stock>1`（我只确认了 Web `PublishView.vue:139,296-298`），P1-4 的跨端范围待 `item-member` 补齐。
- 未核实 `POST /cart/checkout` 在**并发**下"分流后再校验"的最终一致性（`doCreate` 二次读状态，理论上并发仍可能整批回滚），本轮未构造并发场景。
- 未跑 `scripts/verify-*.ps1`（纪律要求），因此 `bookButtonState` 等验收脚本的当前通过状态未知。
- **工作区脏状态说明（供 `lead` 用 `git status` 抽查时对照）**：本轮 `git status` 显示 `ItemService.java`、`JsonCache.java`、`CacheProperties.java`、`README.md`、`开发文档.md`、`开发日志.md` 为已修改，`scripts/audit-cache.mjs` 为未跟踪 —— **这些都不是我改的**（diff 内容为 2026-10-10 的详情缓存 `getOrLoad` 防击穿改造）。我本轮唯一写入 = 本记录文件。我引用的 `ItemService.java` 行号（`369`/`374-379`/`397-407`/`482`）已在写文件前按**当前工作区内容二次复核**，未发生漂移；但其后若有人继续改该文件，行号会再变。

## 7. 下一轮计划

1. **（需改源码）P0-1 修复**：`OrderService.updatePoint` 改时间后条件续锁（`WHERE status=3 AND lock_user_id=?`），并处理影响 0 行的报错路径；顺带把 `accept` 的时间窗口径写进注释。优先级最高，建议单独一人一个文件（`module/order/**`）独占写权。
2. **（需改源码）P1-1 修复**：`OrderService.confirm` 校验 `markSold` 返回值，失败即抛 `ITEM_STATUS_ERROR` 回滚；同时给 `ItemService.update` 加"status=3 时禁止改标题/描述"或在改动后保持 status 不变。跨 `module/item` 与 `module/order`，需 `lead` 指定唯一写者。
3. **（需改源码）P1-2 / P1-3 修复**：把"已有进行中单"并入 `unavailableReason` 的 `skipped` 分流；移动端补「取消」按钮 + `OrderRepository.cancel`。两端各自独立文件，可并行。
4. **（需改源码）P2 批量清理**：统一状态常量命名（`STATUS_CANCELLED=4/STATUS_INVALID=5`）、订正 5 处"买卖双方都能改"注释与 `开发文档.md:1698`、删 `RedisKeys.itemLock/verifyCode` 与核销残留字段、`schema.sql:143` 注释；含 `.md`/`sql` 改动，需 `lead` 明确"非只读轮"。
5. **（需实测）建立回归验证**：补一个可重复的脚本/用例覆盖 P0-1（改期后锁到期时间）与 P1-1（商品在 status=1 时成交），并核对 `credit_log` 是否补齐自动 +2 流水（问题 #3）与购物车清空接口（问题 #10）、`selected` 字段取舍（问题 #12）。

## 8. 与 Leader 的往来

| 时间 | 方向 | 内容 |
| --- | --- | --- |
| 2026-10-14 | 收到 | `lead` 指派 `task-4`：第 1 轮只读核实收藏/购物车/面交/信用分（功能清单 #8/#9/#10/#11/#12 与问题 #3/#10/#12），只写本文件，禁止改源码/配置/文档，禁止起服务与跑 verify 脚本 |
| 2026-10-14 | 提交 | 早期预警（写文件前）：P0 主条「改期后锁库到期时间不同步 → 定时任务提前解锁并作废已确认的面交单，商品回在售可被二次售出」，附 4 处证据（`OrderService.java:366-374`、`:647-661`、`ScheduledTasks.java:58-88`、`OrderController.java:56-60`）与附带 P1（`markSold` 0 行不报错） |
| 2026-10-14 | 收到 | `lead` 裁决：主条**采纳为 P0**（data correctness），要求证据链按触发顺序编号、写明触发条件=「卖家接受后把时间改到 24h 之外」、明确标注"纯静态推断未运行时复现"、第 6 节列清未实测项；`markSold` 条**采纳为 P1** 并补原始代码片段与升级条件；另记 P2「解锁任务作废不发通知」；不需要我改代码，第 2 轮由 `lead` 统一排期 |
| 2026-10-14 | 提交 | `task-4` 标记 `complete`，并向 `lead` 回报（≤15 行：状态 / 记录文件 / Top 发现带证据 / 下一轮动作） |
| 2026-10-14 12:56 | 收到 | **Leader 验收结论：通过**（8 节齐全；30 条已核实结论全带行号；P0×1 / P1×4 / P2×9；第 6 节 9 项未核实显式列出）。P0 主条我已独立复读四处证据（`OrderService.java:366-374`、`:647-661`、`ScheduledTasks.java:58-88`、`OrderService.java:534`），**确认成立**；你标注的"纯静态推断、未运行时复现"符合要求。 |
| 2026-10-14 12:56 | 收到 | **裁定**：① P0 改期不续锁 → 第 2 轮**第一批**（与 app 的 P0 安全条同级），修法走「accept/updatePoint 时按当前 `time_slot_end` 重算并**条件续锁**（`WHERE id=? AND status=3`）」，需同时覆盖"改近"与"改远"两个方向；② P1-1 `markSold` 返回值校验 → 第 2 轮第一批（同一写者，因为它与 P0 在同一文件同一事务边界）；③ P1-2 分流覆盖 40003、P1-3 移动端补取消入口列入第 2 轮次批；④ P1-4 库存语义**先定产品口径**（我倾向"不支持多库存 → 强制 stock=1"），未拍板前不动代码。 |
| 2026-10-14 12:56 | 收到 | P2-2（移动端仍让买家选点并上传 `pointId`，服务端已忽略）**升级关注**：与 map-member 的选点流程直接交叉，第 2 轮由我合并成一个任务、只指定一个写者，避免两端各改一半。第 6 节未实测项（P0/P1 复现、`credit_log` 补流水、`deleted_*` 列）统一由我在唯一实例上安排验证。 |
