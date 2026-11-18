# Nginx + Caffeine + Redis 三级缓存实施方案

## Context

当前项目已有较完整的 Redis 读缓存（空值哨兵、TTL 抖动、Redisson 重建锁），但所有读请求仍要经过网关并访问 Redis；热点商品详情、分类、地图、平台店铺在流量上来后会把 Redis 连接和网关线程占满。本次在不改变业务接口的前提下，引入 Nginx 作为边缘 HTTP 缓存，并在 item/map/platform 三个服务内加 Caffeine 进程内缓存，形成：

```
浏览器/App
  → Nginx :8090（L1，仅匿名 GET）
    → Gateway :8088
      → Service → Caffeine（L2）
        → Redis（L3，现有）
          → MySQL
```

目标：匿名热点读尽量在 Nginx 命中；没命中时在服务内命中 Caffeine；两者都失效才访问 Redis/DB。

## 缓存覆盖与 TTL

| 接口 | Nginx L1 | Caffeine L2 | Redis L3 |
| --- | --- | --- | --- |
| `GET /api/v1/items` | 3s | 不新增（见“范围说明”） | 不新增 |
| `GET /api/v1/items/{id}` | 3s | 5s | 10m（现有） |
| `GET /api/v1/categories` | 10s | 5s | 1h（现有） |
| `GET /api/v1/search` | 3s | 不新增 | 不新增 |
| `GET /api/v1/map/**`、`/trade-points` | 5s | 5s | 60s / 10s（现有） |
| `GET /api/v1/platform/shop/**` | 5s | 5s | 现有 TTL |
| `GET /api/v1/schools` | 30s | 现有字典缓存 | 1h（现有） |
| 登录、购物车、订单、收藏、聊天、管理后台、上传、`/items/mine`、`/ws/**` | 不缓存 | 不缓存 | 不缓存 |

**范围说明**：商品列表和搜索的 query 组合多、单响应大，第一版只加 Nginx 3 秒缓存，不新增 Redis/本地列表缓存，避免列表版本号和内存膨胀；后续确有需要再加。

## 关键设计

1. **Nginx 只缓存匿名 GET**：请求带 `Authorization`、`satoken` Cookie、`X-User-Id` 或 `Cache-Control` 时 `BYPASS` 且不写入缓存。因为商品详情/列表会按登录用户回填 `favorited`，不能共享给匿名缓存。
2. **Caffeine 存原始 JSON 字符串，不存对象**：`ItemService.detail` 命中缓存后会就地修改 `ItemVO`（叠加未回写浏览量、`fillFavorited`）。本地缓存若存对象引用会被污染；存 raw JSON、每次重新反序列化，与现有 Redis 语义一致。
3. **失效策略**：本地缓存短 TTL（5s）+ Redis Pub/Sub 主动失效；Nginx 用 3–10 秒短 TTL，开源 Nginx 无 `proxy_cache_purge`，不做主动清。
4. **Redis 仍是兜底**：空值哨兵、TTL 抖动、Redisson 重建锁保持不变；Caffeine/Nginx 只做加速，不参与正确性。

## 改动清单

### 1. Nginx（Docker）

新增：

- `ershou-cloud/docker/nginx/nginx.conf`
- `ershou-cloud/docker/nginx/conf.d/ershou.conf`
- `ershou-cloud/docker/nginx/snippets/proxy-cache.conf`

`nginx.conf` 要点：

```nginx
proxy_cache_path /var/cache/nginx/ershou levels=1:2 keys_zone=ershou_cache:100m
                 max_size=1g inactive=5m use_temp_path=off;
gzip on;
gzip_types application/json;
include /etc/nginx/conf.d/*.conf;
```

`ershou.conf` 要点：

- `upstream ershou_gateway { server host.docker.internal:8088; keepalive 32; }`
- `map "$http_authorization$cookie_satoken$http_x_user_id$http_cache_control$arg_nocache" $ershou_bypass { default 1; "" 0; }`
- `location = /nginx-health { return 200 "ok\n"; }`
- 各缓存 location 使用 `include /etc/nginx/snippets/proxy-cache.conf;`，再单独设置 `proxy_cache_valid 200 3s|5s|10s|30s;`
- 公共 snippet：
  - `proxy_pass http://ershou_gateway;`
  - `proxy_cache ershou_cache;`
  - `proxy_cache_key "$scheme$request_method$http_host$request_uri";`
  - `proxy_cache_methods GET HEAD;`
  - `proxy_cache_lock on; proxy_cache_lock_timeout 2s;`
  - `proxy_cache_background_update on; proxy_cache_use_stale error timeout updating http_500 http_502 http_503 http_504;`
  - `proxy_cache_bypass $ershou_bypass; proxy_no_cache $ershou_bypass;`
  - `proxy_set_header Accept-Encoding "";`（上游不压缩，Nginx 统一 gzip，避免 `Vary` 碎片）
  - `add_header X-Cache-Status $upstream_cache_status always;`
  - 透传 `Host / X-Real-IP / X-Forwarded-For / X-Forwarded-Proto`
  - **不要** `proxy_ignore_headers Cache-Control Set-Cookie`；让 Nginx 默认拒绝缓存带 `Set-Cookie` 或 `no-store` 的响应。
- `/ws`、`/ws/**` 单独 location：`proxy_http_version 1.1`、`Upgrade/Connection`、`proxy_read_timeout 3600s`、`proxy_buffering off`。
- 其余 `/api/` 统一 `proxy_pass` 不缓存；`client_max_body_size 30m`。

`ershou-cloud/docker/docker-compose.yml` 增加：

```yaml
nginx:
  image: nginx:1.27-alpine
  container_name: ershou-nginx
  ports: ["${NGINX_PORT:-8090}:80"]
  volumes:
    - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
    - ./nginx/conf.d:/etc/nginx/conf.d:ro
    - ./nginx/snippets:/etc/nginx/snippets:ro
    - nginx-cache:/var/cache/nginx
  extra_hosts: ["host.docker.internal:host-gateway"]
  healthcheck:
    test: ["CMD", "wget", "-qO-", "http://127.0.0.1/nginx-health"]
    interval: 10s
    timeout: 3s
    retries: 6
    start_period: 5s
  restart: "no"
  networks: [ershou-cloud-net]
```

`volumes` 增加 `nginx-cache:`。

脚本：`scripts/cloud-up.ps1` 的等待列表加 `ershou-nginx`（health、端口 8090），把“四容器”文案改成“五容器”；`cloud-down.ps1` 无需改。`start-full-cloud.ps1 -Status` 可加一行 Nginx 提示（Docker 管理，不在 jar 列表）。

### 2. 公共包

新增 `ershou-cloud-common/src/main/java/com/ershou/cloud/common/cache/RawLocalCache.java`：

- 基于 Caffeine，只存 `String`；
- `maximumWeight(maxBytes)` + `weigher` 按 UTF-8 字节计；
- `expireAfterWrite(ttl)`；
- `get/put/invalidate(Collection)/invalidateAll()`；
- 不注册 Spring Bean，由各服务按配置 `new`，避免影响 file/gateway 等不需要本地缓存的服务。

新增 `CacheEvictChannel.java`：`NAME = "ershou:cache:evict"`，scope 常量 `item/map/platform`。

`ershou-cloud-common/pom.xml` 增加 Caffeine 依赖（版本由 Spring Boot BOM 管理，不写版本）。三个服务已依赖 common，可直接使用。

### 3. item-service

修改：

- `CacheProperties`：新增
  - `localEnabled=true`
  - `localTtl=5s`
  - `localMaxBytes=33554432`
  - `pubsubEnabled=true`
- `JsonCache`：
  - `@PostConstruct` 初始化 `RawLocalCache`；
  - `get` 先查本地 raw JSON，未命中再查 Redis 并回填本地；
  - `multiGet` 先本地，缺失 key 再 MGET，回填本地；
  - `set/setNull/isNullCached` 同步本地；
  - 新增 `evict(Collection<String> keys)`：删 Redis + 清本地 + 发布失效消息（Redis 删除失败也继续清本地）；
  - 新增 `invalidateLocal(Collection<String>)`、`clearLocal()`、`publishEvict(keys)`。
- `CacheEvictor`：`deleteKeys` 改为调用 `JsonCache.evict(keys)`；保留地图版本号逻辑。
- 新增 `config/CacheEvictConfig.java`：仿 `ChatRedisConfig` 建 `RedisMessageListenerContainer`，订阅 `ershou:cache:evict`，只处理 `item|` 前缀，`item|*` 清全部本地。
- 失效消息格式：`item|ershou:item:detail:123`，逐 key 发布；key 只含冒号/数字，不含 `|`/逗号。
- `ItemService.detail` 不改业务逻辑，L2 由 `JsonCache` 透明接管。

Nacos `ershou-item-service.yaml` 的 `ershou.cache` 段补上述四个配置项。

### 4. map-service

修改：

- `MapProperties`：新增 `localCacheEnabled=true`、`localCacheSeconds=5`、`localMaxBytes=33554432`、`pubsubEnabled=true`。
- `MapCacheService`：
  - `@PostConstruct` 初始化 `RawLocalCache`；
  - `getOrLoad` 先查本地 raw JSON，未命中再查 Redis 并回填；
  - `delete(key)` 删 Redis + 清本地 + 发布 `map|key`；
  - `bumpVersion()` 清空本地并发布 `map|*`（其他实例也清）；版本号本身仍只走 Redis。
- 新增 `config/MapCacheEvictConfig.java` 订阅失效消息，只处理 `map|`。

Nacos `ershou-map-service.yaml` 的 `ershou.map` 段补配置。

### 5. platform-service

修改：

- 新增 `config/PlatformCacheProperties.java`（`@ConfigurationProperties(prefix = "ershou.platform.cache")`）：`localEnabled=true`、`localTtl=5s`、`localMaxBytes=33554432`、`pubsubEnabled=true`。
- `PlatformJsonCache`：
  - `@PostConstruct` 初始化 `RawLocalCache`；
  - `get/getOrLoad` 先查本地；
  - `set/setNull` 同步本地；
  - `delete(Collection<String>)`：删 Redis + 清本地 + 发布 `platform|key`。
- `PlatformCacheService.deleteKeys` 改为调用 `PlatformJsonCache.delete(keys)`，确保 `flash/overview/points/coupons` 等 JSON 缓存失效时本地同步清掉；库存/用户 Hash key 也走同一删除方法（本地无条目，自动 no-op）。
- 新增 `config/PlatformCacheEvictConfig.java` 订阅 `platform|`。

Nacos `ershou-platform-service.yaml` 增加 `ershou.platform.cache.*`。

### 6. 配置汇总

| 服务 | Nacos 配置 |
| --- | --- |
| item | `ershou.cache.local-enabled/local-ttl/local-max-bytes/pubsub-enabled` |
| map | `ershou.map.local-cache-enabled/local-cache-seconds/local-max-bytes/pubsub-enabled` |
| platform | `ershou.platform.cache.local-enabled/local-ttl/local-max-bytes/pubsub-enabled` |

默认值均为开启；`local-enabled=false` 即回滚到 Redis-only。

## 验证

1. 静态校验：`docker compose --env-file .env -f ershou-cloud/docker/docker-compose.yml config -q`；`mvn -f ershou-cloud/pom.xml -DskipTests compile`。
2. 启动：`cloud-up.ps1`（五容器，含 `ershou-nginx`）→ `start-full-cloud.ps1`。
3. 新增 `scripts/verify-three-level-cache.ps1`：
   - `GET http://127.0.0.1:8090/nginx-health` → 200；
   - 连续两次匿名 `GET /api/v1/items`，`X-Cache-Status` 应为 `MISS` → `HIT`；等 4s 后再次请求为 `MISS/EXPIRED`；
   - 带 `Authorization: Bearer dummy` 请求同一接口 → `BYPASS`；
   - `GET /api/v1/items/mine` → 永不 `HIT`；
   - 商品详情两次请求 `MISS → HIT`；`docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD -n 2 TTL ershou:item:detail:<id>` 在 `(0,600]`；
   - map（db5）、platform（db4）同样检查 TTL 与 `X-Cache-Status`；
   - 脚本从 `.env` 读 Redis 密码，不写死。
4. 本地缓存：把 `logging.level.com.ershou.cloud.item.support.cache` 设为 `debug`，连续两次详情请求，日志应出现第一次 miss、第二次 local hit；修改商品后日志应出现本地失效。也可用 `RawLocalCache` 的统计在单测中断言。
5. 回滚验证：Nacos 把 `local-enabled` 改 false 并重启 item，接口仍正常，`X-Cache-Status` 不受影响。

## 风险与取舍

- **Nginx 秒级陈旧**：匿名流量最坏 3–10 秒看到旧数据；写后立即可见性要求高的接口（订单/购物车/收藏/聊天）完全不缓存。
- **用户态泄漏**：带 `Authorization`/Cookie 的请求一律 `BYPASS` 且不写缓存；`Set-Cookie` 响应默认不缓存。
- **Caffeine 内存**：每实例上限 32MB，按字节淘汰；列表/搜索不进入本地缓存，避免大对象。
- **多实例一致性**：Pub/Sub 尽力而为，丢消息由本地 5 秒 TTL 兜底；Nginx 不做主动清。
- **单点**：Nginx 是新增入口，开发环境单容器；生产需要多实例 + keepalived/SLB。
- **端口/网络**：容器通过 `host.docker.internal:8088` 访问宿主机网关；Linux 需 `host-gateway`（已在 compose 配置）。
- **回滚**：Nacos `local-enabled=false` 回 Redis-only；Nginx 可停容器或对请求加 `?nocache=1` 绕过读取；清空 `nginx-cache` 卷即可清空 L1。

## 主要改动文件

- `ershou-cloud/docker/docker-compose.yml`
- `ershou-cloud/docker/nginx/nginx.conf`、`conf.d/ershou.conf`、`snippets/proxy-cache.conf`
- `ershou-cloud/ershou-cloud-common/pom.xml`、`common/cache/RawLocalCache.java`、`CacheEvictChannel.java`
- `ershou-cloud/ershou-item-service/.../support/cache/JsonCache.java`、`CacheEvictor.java`、`CacheProperties.java`、`config/CacheEvictConfig.java`
- `ershou-cloud/ershou-map-service/.../service/MapCacheService.java`、`config/MapProperties.java`、`config/MapCacheEvictConfig.java`
- `ershou-cloud/ershou-platform-service/.../cache/PlatformJsonCache.java`、`service/PlatformCacheService.java`、`config/PlatformCacheProperties.java`、`config/PlatformCacheEvictConfig.java`
- `ershou-cloud/nacos-configs/ershou-item-service.yaml`、`ershou-map-service.yaml`、`ershou-platform-service.yaml`
- `scripts/cloud-up.ps1`、`scripts/verify-three-level-cache.ps1`
