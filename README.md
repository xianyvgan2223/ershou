# 校园二手交易平台

面向高校场景的二手交易信息撮合平台：**轻注册（不实名）+ 校内地图面交 + AI 内容审核**。
平台只做信息撮合与监管，**不介入交易资金**（面交线下自行支付）。

| 模块 | 目录 | 技术栈 |
| --- | --- | --- |
| 需求文档 | `校园二手交易平台需求文档.md` | PRD（含校内地图、AI 审核、毕业季代卖） |
| 开发文档 | `开发文档.md` | 架构、数据库、Redis、**Elasticsearch 搜索**、接口、部署、排期 |
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

### 后端（79 个 Java 文件）

| 模块 | 能力 |
| --- | --- |
| 认证 | 手机号 + 验证码登录（轻注册，**无实名/学籍字段**）；手机号 AES 加密存储 + SHA256 索引；新号 7 天内限额 |
| 商品 | 发布（含 AI 机审）/ 详情 / 列表 / 搜索筛选排序 / 下架 / 我的发布 |
| **搜索** | Elasticsearch 8 索引 `ershou_item`：中文分词（IK→smartcn→ngram 自动探测）、`title^3` 相关性排序、`<em>` 高亮；ES 挂掉或深分页自动降级 MySQL LIKE；索引随写路径实时同步 + 2 分钟增量兜底 + 管理员全量重建 |
| **AI 审核** | 禁售词直接拦截（`50001`）、可疑词转人工（状态"审核中"）；接口抽象，可换 Ollama 或云审核 |
| **校内地图** | 底图配置 + 点位（双坐标）+ 每个点位在售数/最低价 + 面交点推荐 + 用户自定义点位提交 |
| 收藏 | 收藏/取消（唯一索引兜底并发重复） |
| 购物车 | 按卖家分组、增删改、**合并面交结算**（同卖家合并、共享行程号 batchNo） |
| 面交单 | 创建（Redis + DB 条件更新双重锁库 30 分钟）→ 卖家接受/拒绝 → 核销码（一次性、并发兜底）→ 双向确认 → 商品售出 + 双方信用分 +2 |
| 文件 | MinIO 上传（校验 → 压缩长边 1280 → 存桶），MinIO 不可用时可切 `local` 降级 |
| 定时任务 | 释放超时锁库、面交单 2 小时超时失效、商品 60 天过期 |
| 通用 | 统一响应体 + 错误码分段 + 全局异常 + traceId 链路 + 分页 + Swagger |

### 前端（34 个文件）

登录、首页（搜索/分类/排序/分页）、**校内地图**（Leaflet CRS.Simple + 气泡聚合 + 选点）、商品详情、发布（多图上传）、购物车（分组 + 合并面交）、我的面交单（买家/卖家双 tab、核销）、我的收藏、我的发布、个人中心。

## 四、关键验证结果

```
违规内容"管制刀具"        → 50001 内容涉嫌违规：管制刀具（禁售品类）
可疑内容"加微信转账"      → 转人工，商品状态=审核中
购物车 2 件合并面交        → 2 张面交单共享同一 batchNo
核销码错误 / 重复使用      → 40004
双向确认完成              → 商品已售出 + 双方信用分 100 → 102
越权下架他人商品          → 30005
未登录访问需登录接口      → 10002
```

## 五、注意事项

1. **PowerShell 脚本必须带 BOM**（本机 PS 5.1 默认按 GBK 读 .ps1，中文会乱码报错），详见环境清单 4.6。
2. Redis / MinIO / Elasticsearch 容器都是 **restart=no 手动启动**，开机后需要跑 `dev-up.ps1`（ES 首次启动约 30~60 秒）。
3. 代卖（毕业季寄卖）为 V1.2 规划，代码尚未实现，数据库表 `consign_order` 已预留。
4. 聊天 IM 模块后端尚未实现（表 `chat_session`/`chat_message` 已建），前端"私聊"为占位。
