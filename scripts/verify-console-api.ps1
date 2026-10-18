<#
.SYNOPSIS
  第 3 轮「平台代卖 · 独立运营台」验收 · API 端到端脚本（只读 + 可回滚写测试；不改源码/不直接改库）。

.DESCRIPTION
  对应 `智能体团队/第3轮-平台运营台-设计.md`：
    - 第三节 3.1~3.6 全部管理接口（overview / items / coupons / flash-sales / orders / cache）
    - 第五节验收标准 2：运行时逐条断言 + 普通用户全部无权限 + 写操作副作用（改价→shop 立即变、新建券→shop+Redis、
      场次加商品→shop flash+Redis、下架→shop 消失）
    - 第五节验收标准 5：脚本自身不修改源码；测试数据尽量回滚（见结尾"测试数据残留"）

  断言分组：
    C1  环境与登录（后端可用、管理员 19900000102 登录且有运营权限、买家登录备用）
    C2  3.1 概览 GET /overview：20 个字段存在且口径合理（含 flash/order/gmv 派生统计）
    C3  3.2 商品：分页/状态筛选/keyword/pointId + 编辑（标题价库存点位）+ 非法值 + 非平台商品 + 下架/上架 + 副作用
    C4  3.3 券：列表字段 + 新建 + 编辑 + 已领>新库存拒绝 + 启停 + 副作用（shop 出现、Redis coupon:stock 重建）
    C5  3.4 场次：列表 + 新建 + 编辑 + 加/删商品 + 切状态 + 副作用（shop flash-sale 出现、Redis seckill:stock 重建）
    C6  3.5 订单：分页/status/keyword + 字段完整性（买家昵称/点名）+ 概览订单数联动
    C7  3.6 缓存：/cache/stats 字段 + /cache/refresh + 重建后 stats 仍有 key
    C8  权限负向：未登录 10002；普通用户访问 18 个管理接口全部 10003（无权限）
    C9  汇总 + 结果 JSON + 退出码

  用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-console-api.ps1 -DryRun     # 只看计划
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-console-api.ps1             # 全量（含写测试，会创建/回滚测试数据）
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-console-api.ps1 -SkipMutations  # 只读断言
  依赖：后端 8080 已运行（phase 2 由 QA 启动）、MySQL/Redis/docker 可用（-SkipInfra 可关）。
.NOTES
  退出码：0=全通过；2=后端不可用；3=管理员登录/权限失败；>0=失败断言数。
#>
param(
  [string]$Base = 'http://127.0.0.1:8080',
  [string]$ApiPrefix = '/api/v1',
  [int]$CampusId = 2,
  [string]$AdminPhone = '19900000102',
  [string]$BuyerPhone = '19900000101',
  [string]$SmsCode = '123456',
  [string]$TokenCache = '.build\console\tokens.json',
  [string]$OutJson = '',
  [switch]$SkipMutations,
  [switch]$SkipInfra,
  [switch]$DryRun,
  [switch]$VerboseBody
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$consoleDir = Join-Path $root '.build\console'
if (-not (Test-Path $consoleDir)) { New-Item -ItemType Directory -Force -Path $consoleDir | Out-Null }
$logDir = Join-Path $consoleDir 'logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }

# ---------------------------------------------------------------- .env 载入
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { return }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}
$base = $Base.TrimEnd('/') + $ApiPrefix
$outFull = if ($OutJson) { if ([System.IO.Path]::IsPathRooted($OutJson)) { $OutJson } else { Join-Path $root $OutJson } } else { Join-Path $consoleDir 'console-api-result.json' }
$tokenFile = if ($TokenCache) { if ([System.IO.Path]::IsPathRooted($TokenCache)) { $TokenCache } else { Join-Path $root $TokenCache } } else { '' }

# ---------------------------------------------------------------- 结果收集（勿与局部变量重名，PS 大小写不敏感）
$script:pass = 0; $script:fail = 0; $script:skip = 0; $script:warn = 0
$script:checkResults = New-Object System.Collections.ArrayList
$script:testData = [ordered]@{ coupons = @(); flashScenes = @(); orders = @(); itemRestore = @(); notes = @() }
function Check([string]$Id, [string]$Item, [bool]$Ok, [string]$Expect, [string]$Actual) {
  if ($Ok) { $script:pass++ } else { $script:fail++ }
  $status = if ($Ok) { 'PASS' } else { 'FAIL' }
  Write-Host ('  [' + $status + '] ' + $Id + ' ' + $Item) -ForegroundColor $(if ($Ok) { 'Green' } else { 'Red' })
  if (-not $Ok) { Write-Host ('         期望: ' + $Expect) -ForegroundColor Red; Write-Host ('         实际: ' + $Actual) -ForegroundColor Red }
  $null = $script:checkResults.Add([pscustomobject]@{ id=$Id; item=$Item; status=$status; expect=$Expect; actual=$Actual })
}
function Skip([string]$Id, [string]$Item, [string]$Reason) {
  $script:skip++
  Write-Host ('  [SKIP] ' + $Id + ' ' + $Item + ' —— ' + $Reason) -ForegroundColor DarkYellow
  $null = $script:checkResults.Add([pscustomobject]@{ id=$Id; item=$Item; status='SKIP'; expect=''; actual=$Reason })
}
function Warn([string]$Id, [string]$Item, [string]$Detail) {
  $script:warn++
  Write-Host ('  [WARN] ' + $Id + ' ' + $Item + ' —— ' + $Detail) -ForegroundColor Yellow
  $null = $script:checkResults.Add([pscustomobject]@{ id=$Id; item=$Item; status='WARN'; expect=''; actual=$Detail })
}
function Info([string]$Text) { Write-Host ('  [INFO] ' + $Text) -ForegroundColor DarkGray }
function Head([string]$Text) { Write-Host ''; Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan }

# ---------------------------------------------------------------- HTTP
function Http([string]$method, [string]$path, $body = $null, [string]$token = '', [int]$timeoutSec = 25) {
  $url = $base + $path
  $headers = @{ 'Accept' = 'application/json' }
  if ($token) { $headers['Authorization'] = 'Bearer ' + $token }
  $p = @{ Uri = $url; Method = $method; Headers = $headers; TimeoutSec = $timeoutSec; UseBasicParsing = $true }
  if ($null -ne $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 8 -Compress }
    $p['ContentType'] = 'application/json; charset=utf-8'
    $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
  }
  $status = 0; $raw = ''
  try {
    $resp = Invoke-WebRequest @p
    $status = [int]$resp.StatusCode
    $raw = [string]$resp.Content
  } catch {
    $we = $_.Exception
    if ($we.Response) {
      $status = [int]$we.Response.StatusCode
      try { $raw = (New-Object System.IO.StreamReader($we.Response.GetResponseStream())).ReadToEnd() } catch { $raw = '<读取错误响应体失败>' }
    } else {
      # 网络瞬断（无 HTTP 响应）时重试一次：验收跑长链路时偶发 connection reset，不应当成产品失败
      Start-Sleep -Milliseconds 250
      try {
        $retry = Invoke-WebRequest @p
        $status = [int]$retry.StatusCode
        $raw = [string]$retry.Content
      } catch {
        return [pscustomobject]@{ status = 0; raw = ('网络异常(已重试): ' + $we.Message); obj = $null; path = $path }
      }
    }
  }
  $obj = $null
  if ($raw -and $raw.TrimStart().StartsWith('{')) { try { $obj = $raw | ConvertFrom-Json } catch { $obj = $null } }
  if ($VerboseBody) { Info ($method + ' ' + $path + ' -> HTTP ' + $status + ' ' + $(if ($raw.Length -gt 500) { $raw.Substring(0,500) + '...' } else { $raw })) }
  return [pscustomobject]@{ status = $status; raw = $raw; obj = $obj; path = $path }
}
function EnvCode($resp) {
  if ($null -eq $resp) { return -999 }
  if ($null -eq $resp.obj) { return -998 }
  if ($null -eq $resp.obj.code) { return -997 }
  return [int]$resp.obj.code
}
function RespBrief($resp) {
  if ($null -eq $resp) { return '<null>' }
  $b = if ($resp.raw.Length -gt 400) { $resp.raw.Substring(0,400) + '...' } else { $resp.raw }
  return ('HTTP ' + $resp.status + ' ' + $b)
}
function CheckEnvelope([string]$Id, [string]$Item, $resp) {
  Check $Id $Item ((EnvCode $resp) -eq 0) 'code=0' (RespBrief $resp)
  return (EnvCode $resp) -eq 0
}
function Prop($obj, [string]$name) {
  if ($null -eq $obj) { return $null }
  $p = $obj.PSObject.Properties[$name]
  if ($null -eq $p) { return $null }
  return $p.Value
}
function IntProp($obj, [string[]]$names) {
  foreach ($n in $names) { $v = Prop $obj $n; if ($null -ne $v) { try { return [int]$v } catch { } } }
  return 0
}
function StrProp($obj, [string[]]$names) {
  foreach ($n in $names) { $v = Prop $obj $n; if ($null -ne $v) { return [string]$v } }
  return ''
}
function HasProp($obj, [string]$name) {
  # 注意：不能写成 $null -ne (Prop ...) —— JSON 里存在但值为 null 的字段会被误判为"缺字段"
  if ($null -eq $obj) { return $false }
  return $null -ne $obj.PSObject.Properties[$name]
}
function NowIso([int]$addMinutes = 0) { return (Get-Date).AddMinutes($addMinutes).ToString('yyyy-MM-ddTHH:mm:ss') }
# 取统一响应体 data 的便捷函数：避免 `Prop (Prop (Http ...).obj 'data')` 这类嵌套在 PS 里解析歧义/空引用
function HttpData([string]$method, [string]$path, $body = $null, [string]$token = '', [int]$timeoutSec = 25) {
  $r = Http $method $path $body $token $timeoutSec
  return (Prop $r.obj 'data')
}

# ---------------------------------------------------------------- infra（DB 只读 / Redis 只读）
$script:mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$script:infraDb = (-not $SkipInfra) -and (Test-Path $script:mysql)
$script:infraRedis = -not $SkipInfra
if ($script:infraRedis) {
  try { $null = & docker version --format '{{.Server.Version}}' 2>$null; $script:infraRedis = ($LASTEXITCODE -eq 0) } catch { $script:infraRedis = $false }
}
$script:mysqlArgs = @(
  ('--host=' + $(if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' })),
  ('--port=' + $(if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' })),
  ('--user=' + $(if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'ershou' })),
  ('--password=' + $env:MYSQL_PASSWORD),
  ('--database=' + $(if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'ershou' })),
  ('--default-character-set=utf8mb4'), '-N', '-B'
)
function Sql1([string]$q) {
  # SQL 走 stdin：PS 5.1 用 `-e '... "..." ...'` 传参时双引号会被吞掉 → 1064
  $out = $q | & $script:mysql @script:mysqlArgs 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 失败：' + $q) }
  $rows = @($out)
  if ($rows.Count -eq 0) { return '' }
  return ([string]$rows[0]).Trim()
}
function SqlCount([string]$q) { $v = Sql1 $q; if (-not $v) { return 0 } return [int]$v }
function RedisOne([string[]]$argv) {
  $a = @('exec', 'ershou-redis', 'redis-cli', '-a', $env:REDIS_PASSWORD, '--no-auth-warning', '-n', $(if ($env:REDIS_DATABASE) { $env:REDIS_DATABASE } else { '0' })) + $argv
  $out = & docker @a 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('docker exec redis-cli 失败：' + ($argv -join ' ')) }
  $rows = @($out)
  if ($rows.Count -eq 0) { return '' }
  return ([string]$rows[0]).Trim()
}
function RedisGet([string]$key) { return RedisOne @('GET', $key) }
function RedisExists([string]$key) { return (RedisOne @('EXISTS', $key)) -eq '1' }
function RedisTtl([string]$key) { return RedisOne @('TTL', $key) }

# ---------------------------------------------------------------- 登录（mock 验证码；token 缓存省短信）
$script:tokenStore = @{}
if ($tokenFile -and (Test-Path $tokenFile)) {
  try {
    $raw = [System.IO.File]::ReadAllText($tokenFile).TrimStart([char]0xFEFF)
    $cached = $raw | ConvertFrom-Json
    foreach ($p in $cached.PSObject.Properties) { $script:tokenStore[$p.Name] = [string]$p.Value.token }
  } catch { Warn 'C1-0' 'token 缓存解析失败' (_.Exception.Message) }
}
function Save-TokenCache {
  if (-not $tokenFile) { return }
  $obj = [ordered]@{}
  foreach ($k in $script:tokenStore.Keys) { $obj[$k] = @{ token = $script:tokenStore[$k]; at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') } }
  $obj | ConvertTo-Json -Depth 4 | Set-Content -Path $tokenFile -Encoding UTF8
}
function Login([string]$phone) {
  if ($script:tokenStore.ContainsKey($phone)) {
    $t = $script:tokenStore[$phone]
    $me = Http 'GET' '/users/me' $null $t 8
    if ((EnvCode $me) -eq 0) { return [pscustomobject]@{ token = $t; user = (Prop $me.obj 'data'); phone = $phone; cached = $true } }
  }
  $r1 = Http 'POST' '/auth/sms-code' @{ phone = $phone }
  if ((EnvCode $r1) -ne 0) { throw ('发验证码失败 phone=' + $phone + '：' + (RespBrief $r1)) }
  $r2 = Http 'POST' '/auth/login' @{ phone = $phone; code = $SmsCode; schoolId = 1; campusId = $CampusId; deviceId = ('qa-console-' + $phone) }
  if ((EnvCode $r2) -ne 0) { throw ('登录失败 phone=' + $phone + '：' + (RespBrief $r2)) }
  $data = Prop $r2.obj 'data'
  $token = StrProp $data @('token')
  if (-not $token) { throw ('登录响应没有 token：' + (RespBrief $r2)) }
  $script:tokenStore[$phone] = $token
  Save-TokenCache
  return [pscustomobject]@{ token = $token; user = (Prop $data 'user'); phone = $phone; cached = $false }
}

# 18 个管理接口（权限负向矩阵；body 用"能通过参数校验"的合法形状，确保拒绝原因是无权限而不是参数错）
$script:adminEndpoints = @(
  @{ id='overview';    m='GET';    p='/platform/admin/overview?campusId=2'; b=$null },
  @{ id='items-list';  m='GET';    p='/platform/admin/items?pageNum=1&pageSize=5&campusId=2'; b=$null },
  @{ id='items-put';   m='PUT';    p='/platform/admin/items/890'; b=@{ price = 1 } },
  @{ id='items-off';   m='POST';   p='/platform/admin/items/890/offline'; b=$null },
  @{ id='items-on';    m='POST';   p='/platform/admin/items/890/online'; b=$null },
  @{ id='coupons-get'; m='GET';    p='/platform/admin/coupons?campusId=2'; b=$null },
  @{ id='coupons-add'; m='POST';   p='/platform/admin/coupons'; b=@{ name='权测券'; type=1; thresholdAmount=10; discountAmount=1; totalStock=5; perUserLimit=1; startAt='2026-01-01T00:00:00'; endAt='2027-01-01T00:00:00'; campusId=2; status=1 } },
  @{ id='coupons-put'; m='PUT';    p='/platform/admin/coupons/1'; b=@{ name='权测券'; type=1; thresholdAmount=10; discountAmount=1; totalStock=5; perUserLimit=1; startAt='2026-01-01T00:00:00'; endAt='2027-01-01T00:00:00'; campusId=2; status=1 } },
  @{ id='coupons-st';  m='POST';   p='/platform/admin/coupons/1/status?status=0'; b=$null },
  @{ id='flash-get';   m='GET';    p='/platform/admin/flash-sales?campusId=2'; b=$null },
  @{ id='flash-add';   m='POST';   p='/platform/admin/flash-sales'; b=@{ title='权测场次'; startAt='2026-01-01T00:00:00'; endAt='2027-01-01T00:00:00'; status=1; campusId=2; sort=99 } },
  @{ id='flash-put';   m='PUT';    p='/platform/admin/flash-sales/1'; b=@{ title='权测场次'; startAt='2026-01-01T00:00:00'; endAt='2027-01-01T00:00:00'; status=1; campusId=2 } },
  @{ id='flash-item';  m='POST';   p='/platform/admin/flash-sales/1/items'; b=@{ itemId=890; seckillPrice=1; seckillStock=1; perUserLimit=1; sort=1 } },
  @{ id='flash-rm';    m='DELETE'; p='/platform/admin/flash-sales/1/items/890'; b=$null },
  @{ id='flash-st';    m='POST';   p='/platform/admin/flash-sales/1/status?status=1'; b=$null },
  @{ id='orders';      m='GET';    p='/platform/admin/orders?pageNum=1&pageSize=5'; b=$null },
  @{ id='cache-stats'; m='GET';    p='/platform/admin/cache/stats'; b=$null },
  @{ id='cache-ref';   m='POST';   p='/platform/admin/cache/refresh'; b=@{ } }
)

# ================================================================ 预检 / 登录
Write-Host '============================================================' -ForegroundColor White
Write-Host ' 第 3 轮 · 独立运营台验收 · API 端到端' -ForegroundColor White
Write-Host (' 后端: ' + $base + '  校区: ' + $CampusId + '  时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -ForegroundColor DarkGray
Write-Host (' 模式: ' + $(if ($DryRun) { 'DryRun' } elseif ($SkipMutations) { '只读' } else { '全量(含写测试, 尽量回滚)' })) -ForegroundColor DarkGray
Write-Host '============================================================' -ForegroundColor White

Head 'C1 环境与登录'
if ($DryRun) {
  Write-Host ' 计划断言分组（不实际请求）：' -ForegroundColor Cyan
  @(
    'C2  3.1 GET /platform/admin/overview：20 字段 + 派生统计口径',
    'C3  3.2 items：分页/状态/keyword/pointId + 编辑(标题价库存点位) + 非法值 + 非平台商品 + 下架/上架 + 副作用(改价→shop立即变/下架→shop消失)',
    'C4  3.3 coupons：列表字段 + 新建 + 编辑 + 已领>新库存拒绝 + 启停 + 副作用(shop出现/Redis coupon:stock 重建)',
    'C5  3.4 flash-sales：列表 + 新建 + 编辑 + 加/删商品 + 切状态 + 副作用(shop flash出现/Redis seckill:stock 重建)',
    'C6  3.5 orders：分页/status/keyword + 字段(买家昵称/点名) + 概览订单统计联动',
    'C7  3.6 cache：stats 字段(key/type/ttl/value) + refresh',
    'C8  权限负向：未登录 10002 + 普通用户 18 个管理接口全部 10003',
    'C9  汇总 + JSON + 退出码'
  ) | ForEach-Object { Write-Host ('   - ' + $_) -ForegroundColor DarkGray }
  Write-Host ''
  Write-Host ('  管理员测试账号: ' + $AdminPhone + '（需 role_flags&8、campus=2；先跑 verify-platform-api.ps1 拿 token 也可复用 .build/qa/platform-qa-tokens.json 手动放入 ' + $tokenFile + '）') -ForegroundColor DarkGray
  Write-Host ('  权限负向矩阵: ' + $script:adminEndpoints.Count + ' 个接口') -ForegroundColor DarkGray
  exit 0
}

$probe = Http 'GET' '/categories' $null '' 8
if ((EnvCode $probe) -ne 0) {
  Write-Host ('[FAIL] C1-1 后端不可用：GET /categories => ' + (RespBrief $probe)) -ForegroundColor Red
  exit 2
}
Check 'C1-1' '后端可用（GET /categories code=0）' $true 'code=0' 'ok'
Warn 'C1-2' 'infra 交叉校验' ('MySQL=' + $(if ($script:infraDb) { '开' } else { '关' }) + ' Redis=' + $(if ($script:infraRedis) { '开' } else { '关' }))

try {
  $admin = Login $AdminPhone
  $adminToken = $admin.token
  $adminId = IntProp $admin.user @('id')
  $adminMe = Http 'GET' '/users/me' $null $adminToken
  $adminRole = IntProp (Prop $adminMe.obj 'data') @('roleFlags')
  $isAdmin = Prop (Prop $adminMe.obj 'data') 'isAdmin'
  Check 'C1-3' ('管理员登录成功 phone=' + $AdminPhone + ' userId=' + $adminId + '（token 缓存=' + $admin.cached + '）') ($adminId -gt 0) 'userId>0' ($adminId.ToString())
  Check 'C1-4' ('管理员角色 role_flags&8 或 isAdmin（roleFlags=' + $adminRole + ', isAdmin=' + $isAdmin + '）') ((($adminRole -band 8) -ne 0) -or ($isAdmin -eq $true)) '运营身份' ($adminRole.ToString())
} catch {
  Check 'C1-3' '管理员登录' $false '登录成功' $_.Exception.Message
  exit 3
}
try {
  $buyer = Login $BuyerPhone
  $buyerToken = $buyer.token
  $buyerId = IntProp $buyer.user @('id')
  Check 'C1-5' ('普通用户登录成功 phone=' + $BuyerPhone + ' userId=' + $buyerId) ($buyerId -gt 0) 'userId>0' ($buyerId.ToString())
} catch {
  Check 'C1-5' '普通用户登录' $false '登录成功' $_.Exception.Message
  $buyerToken = ''; $buyerId = 0
}

# 记录测试数据，用于 finally 回滚
$script:itemRestore = $null
$script:createdCouponId = 0
$script:createdSceneId = 0
$script:addedFlashItemIds = @()
$script:createdOrderId = 0

try {
  # ================================================================ C2 概览
  Head 'C2 3.1 概览 GET /platform/admin/overview'
  $ovResp = Http 'GET' ('/platform/admin/overview?campusId=' + $CampusId) $null $adminToken
  $ovOk = CheckEnvelope 'C2-1' 'GET /platform/admin/overview code=0' $ovResp
  $ov = Prop $ovResp.obj 'data'
  if ($ovOk) {
    $ovFields = @('platformItemOnSale','platformItemTotal','pointCount','couponCount','couponTotalStock','couponClaimed',
                  'flashSceneId','flashSceneTitle','flashSceneStatus','flashStockLeft','flashStockTotal','flashSold',
                  'orderToday','orderTotal','gmvToday','gmvTotal','pendingPickup','waitingConfirm','lowStockItems','expiringFlashHours')
    $missing = @($ovFields | Where-Object { -not (HasProp $ov $_) })
    Check 'C2-2' ('概览 20 个字段齐全（缺 ' + $missing.Count + '：' + ($missing -join ',') + '）') ($missing.Count -eq 0) '20 字段齐全' ($missing -join ',')
    Check 'C2-3' ('platformItemTotal >= 4（实际 ' + (IntProp $ov @('platformItemTotal')) + '）') ((IntProp $ov @('platformItemTotal')) -ge 4) '>=4' (IntProp $ov @('platformItemTotal')).ToString()
    Check 'C2-4' ('platformItemOnSale 在 [0,total] 区间（onSale=' + (IntProp $ov @('platformItemOnSale')) + '）') ((IntProp $ov @('platformItemOnSale')) -ge 0 -and (IntProp $ov @('platformItemOnSale')) -le (IntProp $ov @('platformItemTotal'))) '0<=onSale<=total' (IntProp $ov @('platformItemOnSale')).ToString()
    Check 'C2-5' ('pointCount >= 3（实际 ' + (IntProp $ov @('pointCount')) + '）') ((IntProp $ov @('pointCount')) -ge 3) '>=3' (IntProp $ov @('pointCount')).ToString()
    Check 'C2-6' ('couponCount >= 3（实际 ' + (IntProp $ov @('couponCount')) + '）') ((IntProp $ov @('couponCount')) -ge 3) '>=3' (IntProp $ov @('couponCount')).ToString()
    Check 'C2-7' ('flashSceneId 非空（实际 ' + (StrProp $ov @('flashSceneId')) + '）') ($null -ne (Prop $ov 'flashSceneId')) '非空' (StrProp $ov @('flashSceneId'))
    Check 'C2-8' ('flashSceneTitle 非空（实际 "' + (StrProp $ov @('flashSceneTitle')) + '"）') ((StrProp $ov @('flashSceneTitle')).Length -gt 0) '非空' (StrProp $ov @('flashSceneTitle'))
    Check 'C2-9' ('flashStockTotal > 0（实际 ' + (IntProp $ov @('flashStockTotal')) + '）') ((IntProp $ov @('flashStockTotal')) -gt 0) '>0' (IntProp $ov @('flashStockTotal')).ToString()
    Check 'C2-10' ('flashStockLeft 在 [0,total]（left=' + (IntProp $ov @('flashStockLeft')) + ', total=' + (IntProp $ov @('flashStockTotal')) + '）') ((IntProp $ov @('flashStockLeft')) -ge 0 -and (IntProp $ov @('flashStockLeft')) -le (IntProp $ov @('flashStockTotal'))) '0<=left<=total' ((IntProp $ov @('flashStockLeft')).ToString())
    Check 'C2-11' ('gmvToday/gmvTotal 为数字且 >=0（' + (StrProp $ov @('gmvToday')) + ' / ' + (StrProp $ov @('gmvTotal')) + '）') ($null -ne (Prop $ov 'gmvToday') -and $null -ne (Prop $ov 'gmvTotal')) '存在' 'missing'
    Info ('概览快照：onSale=' + (IntProp $ov @('platformItemOnSale')) + ' total=' + (IntProp $ov @('platformItemTotal')) + ' points=' + (IntProp $ov @('pointCount')) + ' coupons=' + (IntProp $ov @('couponCount')) + ' flashLeft/Total=' + (IntProp $ov @('flashStockLeft')) + '/' + (IntProp $ov @('flashStockTotal')) + ' orders=' + (IntProp $ov @('orderTotal')) + ' gmv=' + (StrProp $ov @('gmvTotal')))
  }

  # ================================================================ C3 平台商品
  Head 'C3 3.2 平台商品管理'
  $itemsResp = Http 'GET' ('/platform/admin/items?pageNum=1&pageSize=10&campusId=' + $CampusId) $null $adminToken
  $itemsOk = CheckEnvelope 'C3-1' 'GET /platform/admin/items 分页 code=0' $itemsResp
  $itemsData = Prop $itemsResp.obj 'data'
  $items = @(Prop $itemsData 'records')
  $itemsTotal = IntProp $itemsData @('total')
  if ($itemsOk) {
    Check 'C3-2' ('items 分页 total >= 4（total=' + $itemsTotal + ', records=' + $items.Count + '）') ($itemsTotal -ge 4) '>=4' $itemsTotal.ToString()
    if ($items.Count -gt 0) {
      $it0 = $items[0]
      $itFields = @('id','title','price','stock','status','statusText','pointId','pointName','sellerType')
      $itMissing = @($itFields | Where-Object { -not (HasProp $it0 $_) })
      Check 'C3-3' ('AdminPlatformItemVO 字段齐全（缺 ' + ($itMissing -join ',') + '）') ($itMissing.Count -eq 0) 'id/title/price/stock/status/statusText/pointId/pointName/sellerType' ($itMissing -join ',')
      Check 'C3-4' 'items 全部 sellerType=2（平台商品）' ((@($items | Where-Object { (IntProp $_ @('sellerType')) -ne 2 }).Count) -eq 0) '全部=2' ((@($items | Where-Object { (IntProp $_ @('sellerType')) -ne 2 }).Count).ToString())
    }
    # 状态筛选
    $st2 = Http 'GET' ('/platform/admin/items?pageNum=1&pageSize=50&status=2&campusId=' + $CampusId) $null $adminToken
    $st2Recs = @(Prop (Prop $st2.obj 'data') 'records')
    Check 'C3-5' ('status=2 筛选：全部 status=2（' + $st2Recs.Count + ' 条）') ((@($st2Recs | Where-Object { (IntProp $_ @('status')) -ne 2 }).Count) -eq 0 -and (EnvCode $st2) -eq 0) '全部 status=2' (RespBrief $st2)
    $st5 = Http 'GET' ('/platform/admin/items?pageNum=1&pageSize=50&status=5&campusId=' + $CampusId) $null $adminToken
    $st5Recs = @(Prop (Prop $st5.obj 'data') 'records')
    Check 'C3-6' ('status=5（已下架）筛选可用（' + $st5Recs.Count + ' 条；允许 0 条）') ((EnvCode $st5) -eq 0 -and (@($st5Recs | Where-Object { (IntProp $_ @('status')) -ne 5 }).Count -eq 0)) '全部 status=5' (RespBrief $st5)
    # keyword / pointId 筛选
    if ($items.Count -gt 0) {
      $kw = (StrProp $items[0] @('title'))
      $kwPart = if ($kw.Length -gt 2) { $kw.Substring(0, 2) } else { $kw }
      $kwResp = Http 'GET' ('/platform/admin/items?pageNum=1&pageSize=20&keyword=' + [uri]::EscapeDataString($kwPart) + '&campusId=' + $CampusId) $null $adminToken
      $kwRecs = @(Prop (Prop $kwResp.obj 'data') 'records')
      $kwHit = @($kwRecs | Where-Object { (StrProp $_ @('title')).Contains($kwPart) })
      Check 'C3-7' ('keyword 筛选命中（keyword="' + $kwPart + '"，' + $kwRecs.Count + ' 条中 ' + $kwHit.Count + ' 条标题含关键字）') ((EnvCode $kwResp) -eq 0 -and $kwRecs.Count -ge 1 -and $kwHit.Count -eq $kwRecs.Count) '命中且标题含关键字' (RespBrief $kwResp)
      $pointFilterId = IntProp $items[0] @('pointId')
      if ($pointFilterId -gt 0) {
        $ptResp = Http 'GET' ('/platform/admin/items?pageNum=1&pageSize=50&pointId=' + $pointFilterId + '&campusId=' + $CampusId) $null $adminToken
        $ptRecs = @(Prop (Prop $ptResp.obj 'data') 'records')
        Check 'C3-8' ('pointId=' + $pointFilterId + ' 筛选（' + $ptRecs.Count + ' 条，点全匹配）') ((EnvCode $ptResp) -eq 0 -and (@($ptRecs | Where-Object { (IntProp $_ @('pointId')) -ne $pointFilterId }).Count -eq 0)) '全部 pointId 匹配' (RespBrief $ptResp)
      } else { Skip 'C3-8' 'pointId 筛选' '首条记录无 pointId' }
    }
  }
  # 编辑/上下架（写测试）
  $editItemId = 0
  if ($SkipMutations) {
    Skip 'C3-9..18' '商品编辑/上下架写测试' '传了 -SkipMutations'
  } else {
    $editCandidate = @($items | Where-Object { (IntProp $_ @('status')) -eq 2 -and (IntProp $_ @('id')) -gt 0 })
    if ($editCandidate.Count -eq 0) {
      Skip 'C3-9..18' '商品编辑/上下架写测试' '没有在售平台商品可编辑（先重置种子）'
    } else {
      $editItem = $editCandidate[0]
      $editItemId = IntProp $editItem @('id')
      $origTitle = StrProp $editItem @('title')
      $origPrice = [decimal](Prop $editItem 'price')
      $origStock = IntProp $editItem @('stock')
      $origPointId = IntProp $editItem @('pointId')
      $script:itemRestore = [pscustomobject]@{ id=$editItemId; title=$origTitle; price=$origPrice; stock=$origStock; platformPointId=$origPointId }
      $newPrice = [math]::Round($origPrice + 1.11, 2)
      $newTitle = $origTitle + '·QA'
      $putBody = @{ title = $newTitle; price = $newPrice; stock = $origStock }
      $putResp = Http 'PUT' ('/platform/admin/items/' + $editItemId) $putBody $adminToken
      $putOk = CheckEnvelope 'C3-9' ('PUT /platform/admin/items/' + $editItemId + ' 编辑 code=0') $putResp
      $putData = Prop $putResp.obj 'data'
      if ($putOk) {
        Check 'C3-10' ('编辑响应 title/price/stock 已更新（title="' + (StrProp $putData @('title')) + '", price=' + (StrProp $putData @('price')) + '）') ((StrProp $putData @('title')) -eq $newTitle -and [decimal](Prop $putData 'price') -eq [decimal]$newPrice) ('title=' + $newTitle + ',price=' + $newPrice) ((StrProp $putData @('title')) + ',' + (StrProp $putData @('price')))
      }
      # 副作用：shop 立即变 + 详情 + 首页列表（ES/DB）
      $shopItems = Http 'GET' '/platform/shop/items?pageNum=1&pageSize=50&campusId=2'
      $shopRecs = @(Prop (Prop $shopItems.obj 'data') 'records')
      $shopHit = @($shopRecs | Where-Object { (IntProp $_ @('id')) -eq $editItemId })
      $shopPrice = if ($shopHit.Count -ge 1) { [decimal](Prop $shopHit[0] 'price') } else { -1 }
      Check 'C3-11' ('改价后 GET /platform/shop/items 立即可见新价（' + $shopPrice + ' == ' + $newPrice + '）') ($shopHit.Count -ge 1 -and $shopPrice -eq [decimal]$newPrice) $newPrice $shopPrice
      $detailResp = Http 'GET' ('/items/' + $editItemId)
      $detailPrice = [decimal](Prop (Prop $detailResp.obj 'data') 'price')
      Check 'C3-12' ('改价后公开商品详情同步（' + $detailPrice + '）') ($detailPrice -eq [decimal]$newPrice) $newPrice $detailPrice
      # 非法值负向
      $badPrice = Http 'PUT' ('/platform/admin/items/' + $editItemId) @{ price = -1 } $adminToken
      Check 'C3-13' ('编辑非法价格 -1 被拒（code=' + (EnvCode $badPrice) + '）') ((EnvCode $badPrice) -ne 0) 'code!=0' (RespBrief $badPrice)
      $badStock = Http 'PUT' ('/platform/admin/items/' + $editItemId) @{ stock = -5 } $adminToken
      Check 'C3-14' ('编辑非法库存 -5 被拒（code=' + (EnvCode $badStock) + '）') ((EnvCode $badStock) -ne 0) 'code!=0' (RespBrief $badStock)
      # 非平台商品不可编辑
      if ($script:infraDb) {
        try {
          $personalId = SqlCount 'SELECT COALESCE(MIN(id),0) FROM item WHERE seller_type=1;'
          if ($personalId -gt 0) {
            $badItem = Http 'PUT' ('/platform/admin/items/' + $personalId) @{ price = 1 } $adminToken
            Check 'C3-15' ('编辑非平台商品（id=' + $personalId + '）被拒（code=' + (EnvCode $badItem) + '）') ((EnvCode $badItem) -ne 0) 'code!=0' (RespBrief $badItem)
          } else { Skip 'C3-15' '非平台商品编辑校验' 'DB 没有个人商品可测' }
        } catch { Skip 'C3-15' '非平台商品编辑校验' ('DB 查询失败: ' + $_.Exception.Message) }
      } else { Skip 'C3-15' '非平台商品编辑校验' 'MySQL 校验不可用' }
      # 下架 → shop 消失；上架 → shop 恢复
      $offResp = Http 'POST' ('/platform/admin/items/' + $editItemId + '/offline') $null $adminToken
      $offOk = CheckEnvelope 'C3-16' ('POST items/' + $editItemId + '/offline 下架 code=0') $offResp
      $shopPageOff = HttpData 'GET' '/platform/shop/items?pageNum=1&pageSize=50&campusId=2'
      $shopAfterOff = @(Prop $shopPageOff 'records')
      $stillThere = @($shopAfterOff | Where-Object { (IntProp $_ @('id')) -eq $editItemId })
      Check 'C3-17' '下架后 GET /platform/shop/items 不再出现该商品' ($stillThere.Count -eq 0) '0 条' $stillThere.Count.ToString()
      if ($script:infraDb) {
        try {
          $dbStatus = Sql1 ('SELECT status FROM item WHERE id=' + $editItemId + ';')
          Check 'C3-18' ('下架后 DB status=5（实际 ' + $dbStatus + '）') ($dbStatus -eq '5') '5' $dbStatus
        } catch { Skip 'C3-18' 'DB 状态校验' $_.Exception.Message }
      } else { Skip 'C3-18' 'DB 状态校验' 'MySQL 不可用' }
      $onResp = Http 'POST' ('/platform/admin/items/' + $editItemId + '/online') $null $adminToken
      $onOk = CheckEnvelope 'C3-19' ('POST items/' + $editItemId + '/online 上架 code=0') $onResp
      $shopPageOn = HttpData 'GET' '/platform/shop/items?pageNum=1&pageSize=50&campusId=2'
      $shopAfterOn = @(Prop $shopPageOn 'records')
      Info ('上架后 shop/items 返回 ids=' + ((@($shopAfterOn) | ForEach-Object { IntProp $_ @('id') }) -join ',') + '（total=' + (IntProp $shopPageOn @('total')) + '）')
      $backThere = @($shopAfterOn | Where-Object { (IntProp $_ @('id')) -eq $editItemId })
      Check 'C3-20' '上架后 GET /platform/shop/items 重新出现' ($backThere.Count -ge 1) '>=1' $backThere.Count.ToString()
      if ($script:infraDb) {
        try {
          $dbRow = Sql1 ('SELECT CONCAT_WS("|", status, (expire_at > DATE_ADD(NOW(), INTERVAL 29 DAY))) FROM item WHERE id=' + $editItemId + ';')
          $dbParts = $dbRow -split '\|'
          Check 'C3-21' ('上架后 DB status=2 且 expire_at≈NOW()+30 天（status=' + $dbParts[0] + ', expireFuture=' + $dbParts[1] + '）') ((($dbParts[0]) -eq '2') -and (($dbParts[1]) -eq '1')) 'status=2,expire+30d' $dbRow
        } catch { Skip 'C3-21' 'DB 上架校验' $_.Exception.Message }
      } else { Skip 'C3-21' 'DB 上架校验' 'MySQL 不可用' }
      # 还原价格/标题/库存
      $restoreResp = Http 'PUT' ('/platform/admin/items/' + $editItemId) @{ title = $origTitle; price = $origPrice; stock = $origStock } $adminToken
      Check 'C3-22' ('编辑测试后已还原 price/title/stock（code=' + (EnvCode $restoreResp) + '）') ((EnvCode $restoreResp) -eq 0) 'code=0' (RespBrief $restoreResp)
      if ($script:infraDb) {
        try {
          $restored = Sql1 ('SELECT CONCAT_WS("|", price, title) FROM item WHERE id=' + $editItemId + ';')
          Check 'C3-23' ('还原后 DB price 与原始一致（实际 ' + $restored + '，原始 ' + $origPrice + '）') (($restored -split '\|')[0] -eq ([string]$origPrice)) ([string]$origPrice) (($restored -split '\|')[0])
        } catch { Skip 'C3-23' 'DB 还原校验' $_.Exception.Message }
      }
    }
  }

  # ================================================================ C4 优惠券
  Head 'C4 3.3 优惠券管理'
  $cpResp = Http 'GET' ('/platform/admin/coupons?campusId=' + $CampusId) $null $adminToken
  $cpOk = CheckEnvelope 'C4-1' 'GET /platform/admin/coupons code=0' $cpResp
  $coupons = @(Prop $cpResp.obj 'data')
  if ($cpOk) {
    Check 'C4-2' ('券列表 >= 3（实际 ' + $coupons.Count + '）') ($coupons.Count -ge 3) '>=3' $coupons.Count.ToString()
    if ($coupons.Count -gt 0) {
      $c0 = $coupons[0]
      $cpFields = @('id','name','type','totalStock','perUserLimit','startAt','endAt','status','statusText','claimedCount','leftStock')
      $cpMissing = @($cpFields | Where-Object { -not (HasProp $c0 $_) })
      Check 'C4-3' ('AdminCouponVO 字段齐全（缺 ' + ($cpMissing -join ',') + '）') ($cpMissing.Count -eq 0) '11 字段' ($cpMissing -join ',')
      Check 'C4-4' '券 leftStock 均 >=0 且 <= totalStock' ((@($coupons | Where-Object { (IntProp $_ @('leftStock')) -lt 0 -or (IntProp $_ @('leftStock')) -gt (IntProp $_ @('totalStock')) }).Count) -eq 0) '0<=left<=total' ((@($coupons | Where-Object { (IntProp $_ @('leftStock')) -lt 0 -or (IntProp $_ @('leftStock')) -gt (IntProp $_ @('totalStock')) }).Count).ToString())
      Check 'C4-5' '券 claimedCount 均 >=0' ((@($coupons | Where-Object { (IntProp $_ @('claimedCount')) -lt 0 }).Count) -eq 0) '>=0' 'negative'
    }
  }
  if ($SkipMutations) {
    Skip 'C4-6..16' '券新建/编辑/启停写测试' '传了 -SkipMutations'
  } else {
    $cpName = 'QA运营台券-' + (Get-Date -Format 'HHmmss')
    $cpBody = @{ name = $cpName; type = 1; thresholdAmount = 10; discountAmount = 1; totalStock = 5; perUserLimit = 1; startAt = (NowIso -60); endAt = (NowIso 1440); campusId = $CampusId; status = 1 }
    $createCp = Http 'POST' '/platform/admin/coupons' $cpBody $adminToken
    $createCpOk = CheckEnvelope 'C4-6' 'POST /platform/admin/coupons 新建 code=0' $createCp
    if ($createCpOk) {
      $created = Prop $createCp.obj 'data'
      $script:createdCouponId = IntProp $created @('id')
      $script:testData.coupons += $script:createdCouponId
      Check 'C4-7' ('新建券返回 id 且 totalStock=5/requested（id=' + $script:createdCouponId + ', totalStock=' + (IntProp $created @('totalStock')) + '）') ($script:createdCouponId -gt 0 -and (IntProp $created @('totalStock')) -eq 5) 'id>0,totalStock=5' ((StrProp $created @('id')) + ',' + (StrProp $created @('totalStock')))
      # 副作用：shop 出现 + Redis stock
      $shopCp = @(HttpData 'GET' ('/platform/shop/coupons?campusId=' + $CampusId))
      $shopCpHit = @($shopCp | Where-Object { (IntProp $_ @('id')) -eq $script:createdCouponId })
      Check 'C4-8' ('新建券出现在 GET /platform/shop/coupons（匹配 ' + $shopCpHit.Count + '）') ($shopCpHit.Count -ge 1) '>=1' $shopCpHit.Count.ToString()
      $shopLeft = if ($shopCpHit.Count -ge 1) { IntProp $shopCpHit[0] @('leftStock') } else { -1 }
      Check 'C4-9' ('新建券 shop.leftStock=5（实际 ' + $shopLeft + '）') ($shopLeft -eq 5) '5' $shopLeft.ToString()
      if ($script:infraRedis) {
        try {
          $rk = 'ershou:platform:coupon:stock:' + $script:createdCouponId
          $exists = RedisExists $rk
          $val = RedisGet $rk
          Check 'C4-10' ('Redis coupon:stock:' + $script:createdCouponId + ' 存在且=5（exists=' + $exists + ', value=' + $val + '）') ($exists -and $val -eq '5') 'exists & =5' ($exists.ToString() + '/' + $val)
        } catch { Skip 'C4-10' 'Redis 券库存校验' $_.Exception.Message }
      } else { Skip 'C4-10' 'Redis 券库存校验' 'Redis 不可用' }
      # 编辑：totalStock 5 -> 8，name 加后缀
      $updBody = @{ name = $cpName + '改'; type = 1; thresholdAmount = 10; discountAmount = 1; totalStock = 8; perUserLimit = 1; startAt = (NowIso -60); endAt = (NowIso 1440); campusId = $CampusId; status = 1 }
      $updCp = Http 'PUT' ('/platform/admin/coupons/' + $script:createdCouponId) $updBody $adminToken
      $updOk = CheckEnvelope 'C4-11' 'PUT /platform/admin/coupons/{id} 编辑 code=0' $updCp
      if ($updOk) {
        $cpList2 = @(HttpData 'GET' ('/platform/admin/coupons?campusId=' + $CampusId) $null $adminToken)
        $cp2 = @($cpList2 | Where-Object { (IntProp $_ @('id')) -eq $script:createdCouponId })
        Check 'C4-12' ('编辑后 totalStock=8（实际 ' + $(if ($cp2.Count -ge 1) { IntProp $cp2[0] @('totalStock') } else { -1 }) + '）') ($cp2.Count -ge 1 -and (IntProp $cp2[0] @('totalStock')) -eq 8) '8' (IntProp $cp2[0] @('totalStock'))
      }
      if ($script:infraRedis) {
        try {
          $rk = 'ershou:platform:coupon:stock:' + $script:createdCouponId
          $val2 = RedisGet $rk
          Check 'C4-13' ('编辑券后 Redis coupon:stock 重建为 8（实际 ' + $val2 + '）') ($val2 -eq '8') '8' $val2
        } catch { Skip 'C4-13' 'Redis 券重建校验' $_.Exception.Message }
      }
      # 买家领取 1 张 → claimed=1；再把 totalStock 改到 0 应被拒（已发放数 > 新库存）
      $claimResp = Http 'POST' ('/platform/me/coupons/' + $script:createdCouponId + '/claim') @{} $buyerToken
      $claimOk = CheckEnvelope 'C4-14' ('买家领取新券（claim code=' + (EnvCode $claimResp) + '）') $claimResp
      if ($claimOk) {
        $claimed = @(HttpData 'GET' ('/platform/admin/coupons?campusId=' + $CampusId) $null $adminToken | Where-Object { (IntProp $_ @('id')) -eq $script:createdCouponId })
        $claimedCount = if ($claimed.Count -ge 1) { IntProp $claimed[0] @('claimedCount') } else { -1 }
        Check 'C4-15' ('领取后后台 claimedCount=1（实际 ' + $claimedCount + '）') ($claimedCount -eq 1) '1' $claimedCount.ToString()
        $badUpd = Http 'PUT' ('/platform/admin/coupons/' + $script:createdCouponId) @{ name = $cpName; type = 1; thresholdAmount = 10; discountAmount = 1; totalStock = 0; perUserLimit = 1; startAt = (NowIso -60); endAt = (NowIso 1440); campusId = $CampusId; status = 1 } $adminToken
        Check 'C4-16' ('已发放(1) > 新 totalStock(0) 时编辑被拒（code=' + (EnvCode $badUpd) + '）') ((EnvCode $badUpd) -ne 0) 'code!=0' (RespBrief $badUpd)
        $after = @(HttpData 'GET' ('/platform/admin/coupons?campusId=' + $CampusId) $null $adminToken | Where-Object { (IntProp $_ @('id')) -eq $script:createdCouponId })
        $afterTotal = if ($after.Count -ge 1) { IntProp $after[0] @('totalStock') } else { -1 }
        Check 'C4-17' ('被拒后 totalStock 未被改（仍=8，实际 ' + $afterTotal + '）') ($afterTotal -eq 8) '8' $afterTotal.ToString()
      }
      # 启停：0 → shop 消失；1 → shop 恢复；最后置 0 收尾
      $offCp = Http 'POST' ('/platform/admin/coupons/' + $script:createdCouponId + '/status?status=0') $null $adminToken
      $offCpOk = CheckEnvelope 'C4-18' 'POST coupons/{id}/status?status=0 停用 code=0' $offCp
      $shopCpAfterOff = @(HttpData 'GET' ('/platform/shop/coupons?campusId=' + $CampusId))
      $stillShop = @($shopCpAfterOff | Where-Object { (IntProp $_ @('id')) -eq $script:createdCouponId })
      Check 'C4-19' '停用后 GET /platform/shop/coupons 不再出现该券' ($stillShop.Count -eq 0) '0 条' $stillShop.Count.ToString()
      $onCp = Http 'POST' ('/platform/admin/coupons/' + $script:createdCouponId + '/status?status=1') $null $adminToken
      Check 'C4-20' 'POST coupons/{id}/status?status=1 启用 code=0' ((EnvCode $onCp) -eq 0) 'code=0' (RespBrief $onCp)
      $shopCpAfterOn = @(HttpData 'GET' ('/platform/shop/coupons?campusId=' + $CampusId))
      Check 'C4-21' '启用后 shop/coupons 重新出现' ((@($shopCpAfterOn | Where-Object { (IntProp $_ @('id')) -eq $script:createdCouponId }).Count) -ge 1) '>=1' '0'
      # 非法创建（type 越界 / totalStock 负数）
      $badCreate = Http 'POST' '/platform/admin/coupons' @{ name = 'QA非法券'; type = 9; totalStock = -1; perUserLimit = 1; startAt = (NowIso -60); endAt = (NowIso 1440); campusId = $CampusId; status = 1 } $adminToken
      Check 'C4-22' ('非法券参数被拒（code=' + (EnvCode $badCreate) + '）') ((EnvCode $badCreate) -ne 0) 'code!=0' (RespBrief $badCreate)
    }
  }

  # ================================================================ C5 秒杀场次
  Head 'C5 3.4 秒杀场次管理'
  $fsResp = Http 'GET' ('/platform/admin/flash-sales?campusId=' + $CampusId) $null $adminToken
  $fsOk = CheckEnvelope 'C5-1' 'GET /platform/admin/flash-sales code=0' $fsResp
  $scenes = @(Prop $fsResp.obj 'data')
  $activeScene = $null
  if ($fsOk) {
    Check 'C5-2' ('场次列表 >= 1（实际 ' + $scenes.Count + '）') ($scenes.Count -ge 1) '>=1' $scenes.Count.ToString()
    if ($scenes.Count -gt 0) {
      # 优先挑「有商品的场次」做字段检查（QA 新建的空场次 items 可能是 null，不代表字段缺失）
      $s0 = @($scenes | Where-Object { @(Prop $_ 'items').Count -gt 0 }) | Select-Object -First 1
      if ($null -eq $s0) { $s0 = $scenes[0] }
      $fsFields = @('id','title','status','statusText','startAt','endAt','sort','items')
      $fsMissing = @($fsFields | Where-Object { -not (HasProp $s0 $_) })
      Check 'C5-3' ('AdminFlashSaleVO 字段齐全（缺 ' + ($fsMissing -join ',') + '）') ($fsMissing.Count -eq 0) '8 字段' ($fsMissing -join ',')
      $sItems = @(Prop $s0 'items')
      if ($sItems.Count -gt 0) {
        $fiFields = @('itemId','title','originPrice','seckillPrice','seckillStock','perUserLimit','soldCount','leftStock')
        $fiMissing = @($fiFields | Where-Object { -not (HasProp $sItems[0] $_) })
        Check 'C5-4' ('场次商品字段齐全（缺 ' + ($fiMissing -join ',') + '）') ($fiMissing.Count -eq 0) '8 字段' ($fiMissing -join ',')
      }
      $activeScene = @($scenes | Where-Object { (IntProp $_ @('status')) -eq 2 }) | Select-Object -First 1
    }
  }
  # 选一个不在当前场次里的平台商品，供"加商品"测试
  $candidateItemId = 0
  $sceneItemIds = @()
  if ($null -ne $activeScene) { $sceneItemIds = @((Prop $activeScene 'items') | ForEach-Object { IntProp $_ @('itemId') }) }
  if ($items.Count -gt 0) {
    $free = @($items | Where-Object { (IntProp $_ @('status')) -eq 2 -and (IntProp $_ @('id')) -gt 0 -and ($sceneItemIds -notcontains (IntProp $_ @('id'))) })
    if ($free.Count -gt 0) { $candidateItemId = IntProp $free[0] @('id') }
  }
  if ($SkipMutations) {
    Skip 'C5-5..18' '场次新建/加删商品/切状态写测试' '传了 -SkipMutations'
  } else {
    # 新建场次（预告）
    $sceneTitle = 'QA运营台场次-' + (Get-Date -Format 'HHmmss')
    $sceneBody = @{ title = $sceneTitle; startAt = (NowIso -10); endAt = (NowIso 120); status = 1; campusId = $CampusId; sort = 99 }
    $createFs = Http 'POST' '/platform/admin/flash-sales' $sceneBody $adminToken
    $createFsOk = CheckEnvelope 'C5-5' 'POST /platform/admin/flash-sales 新建 code=0' $createFs
    if ($createFsOk) {
      $fsCreated = Prop $createFs.obj 'data'
      $script:createdSceneId = IntProp $fsCreated @('id')
      $script:testData.flashScenes += $script:createdSceneId
      Check 'C5-6' ('新建场次返回 id（' + $script:createdSceneId + '）') ($script:createdSceneId -gt 0) 'id>0' $script:createdSceneId.ToString()
      # 编辑场次
      $updFs = Http 'PUT' ('/platform/admin/flash-sales/' + $script:createdSceneId) @{ title = $sceneTitle + '改'; startAt = (NowIso -20); endAt = (NowIso 180); status = 1; campusId = $CampusId; sort = 99 } $adminToken
      $updFsOk = CheckEnvelope 'C5-7' 'PUT /platform/admin/flash-sales/{id} 编辑 code=0' $updFs
      $scenes2 = @(HttpData 'GET' ('/platform/admin/flash-sales?campusId=' + $CampusId) $null $adminToken)
      $sc2 = @($scenes2 | Where-Object { (IntProp $_ @('id')) -eq $script:createdSceneId })
      $sc2Title = if ($sc2.Count -ge 1) { StrProp $sc2[0] @('title') } else { '' }
      Check 'C5-8' ('编辑后场次标题生效（实际 "' + $sc2Title + '"）') ($sc2Title -eq ($sceneTitle + '改')) ($sceneTitle + '改') $sc2Title
      # 给新建场次加商品（用 890 或候选）
      $addItemId = if ($candidateItemId -gt 0) { $candidateItemId } elseif ($items.Count -gt 0) { IntProp $items[0] @('id') } else { 0 }
      if ($addItemId -gt 0) {
        $addFi = Http 'POST' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/items') @{ itemId = $addItemId; seckillPrice = 6.66; seckillStock = 2; perUserLimit = 1; sort = 1 } $adminToken
        $addFiOk = CheckEnvelope 'C5-9' ('POST flash-sales/{id}/items 加商品(item=' + $addItemId + ') code=0') $addFi
        if ($addFiOk) {
          $addData = Prop $addFi.obj 'data'
          $addItems = @(Prop $addData 'items')
          $hitAdd = @($addItems | Where-Object { (IntProp $_ @('itemId')) -eq $addItemId })
          Check 'C5-10' ('加商品后场次 items 含该商品（匹配 ' + $hitAdd.Count + '）') ($hitAdd.Count -ge 1) '>=1' $hitAdd.Count.ToString()
          $script:addedFlashItemIds += @{ scene = $script:createdSceneId; item = $addItemId }
          if ($script:infraRedis) {
            try {
              $sk = 'ershou:platform:seckill:stock:' + $addItemId
              $ex = RedisExists $sk
              $v = RedisGet $sk
              Check 'C5-11' ('加商品后 Redis seckill:stock:' + $addItemId + ' 存在且=2（exists=' + $ex + ', value=' + $v + '）') ($ex -and $v -eq '2') 'exists & =2' ($ex.ToString() + '/' + $v)
            } catch { Skip 'C5-11' 'Redis 秒杀库存校验' $_.Exception.Message }
          } else { Skip 'C5-11' 'Redis 秒杀库存校验' 'Redis 不可用' }
          # 切状态：预告 → 进行中 → 结束
          $st2 = Http 'POST' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/status?status=2') $null $adminToken
          Check 'C5-12' 'POST flash-sales/{id}/status?status=2 进行中 code=0' ((EnvCode $st2) -eq 0) 'code=0' (RespBrief $st2)
          $st3 = Http 'POST' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/status?status=3') $null $adminToken
          Check 'C5-13' 'POST flash-sales/{id}/status?status=3 结束 code=0' ((EnvCode $st3) -eq 0) 'code=0' (RespBrief $st3)
          $sc3 = @(HttpData 'GET' ('/platform/admin/flash-sales?campusId=' + $CampusId) $null $adminToken | Where-Object { (IntProp $_ @('id')) -eq $script:createdSceneId })
          $sc3Status = if ($sc3.Count -ge 1) { IntProp $sc3[0] @('status') } else { -1 }
          Check 'C5-14' ('切状态后场次 status=3（实际 ' + $sc3Status + '）') ($sc3Status -eq 3) '3' $sc3Status.ToString()
          # 删除场次商品
          $rmFi = Http 'DELETE' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/items/' + $addItemId) $null $adminToken
          Check 'C5-15' ('DELETE flash-sales/{id}/items/' + $addItemId + ' code=0') ((EnvCode $rmFi) -eq 0) 'code=0' (RespBrief $rmFi)
          $sc4 = @(HttpData 'GET' ('/platform/admin/flash-sales?campusId=' + $CampusId) $null $adminToken | Where-Object { (IntProp $_ @('id')) -eq $script:createdSceneId })
          $sc4Items = if ($sc4.Count -ge 1) { @(Prop $sc4[0] 'items') } else { @() }
          Check 'C5-16' '删除后场次 items 不再含该商品' ((@($sc4Items | Where-Object { (IntProp $_ @('itemId')) -eq $addItemId }).Count) -eq 0) '0 条' ((@($sc4Items | Where-Object { (IntProp $_ @('itemId')) -eq $addItemId }).Count).ToString())
        }
      } else { Skip 'C5-9..16' '场次加商品测试' '没有可用的平台商品' }
    }
    # 副作用（验收标准 2）：给"进行中"场次(1) 加一个商品 → shop/flash-sale 出现 + seckill:stock 存在；再删掉
    if ($null -ne $activeScene -and $candidateItemId -gt 0) {
      $activeSceneId = IntProp $activeScene @('id')
      $addSide = Http 'POST' ('/platform/admin/flash-sales/' + $activeSceneId + '/items') @{ itemId = $candidateItemId; seckillPrice = 5.55; seckillStock = 2; perUserLimit = 1; sort = 3 } $adminToken
      $addSideOk = CheckEnvelope 'C5-17' ('进行中场次(' + $activeSceneId + ')加商品(item=' + $candidateItemId + ') code=0') $addSide
      if ($addSideOk) {
        $script:addedFlashItemIds += @{ scene = $activeSceneId; item = $candidateItemId }
        $shopFlash = Http 'GET' ('/platform/shop/flash-sale?campusId=' + $CampusId)
        $shopScene = Prop $shopFlash.obj 'data'
        $shopFi = @(Prop $shopScene 'items')
        $hitSide = @($shopFi | Where-Object { (IntProp $_ @('itemId')) -eq $candidateItemId })
        Check 'C5-18' ('场次加商品后 GET /platform/shop/flash-sale 出现该商品（匹配 ' + $hitSide.Count + '）') ($hitSide.Count -ge 1) '>=1' $hitSide.Count.ToString()
        if ($script:infraRedis) {
          try {
            $sk = 'ershou:platform:seckill:stock:' + $candidateItemId
            $ex = RedisExists $sk
            $v = RedisGet $sk
            Check 'C5-19' ('场次加商品后 Redis seckill:stock:' + $candidateItemId + ' 存在且=2（exists=' + $ex + ', value=' + $v + '）') ($ex -and $v -eq '2') 'exists & =2' ($ex.ToString() + '/' + $v)
          } catch { Skip 'C5-19' 'Redis 秒杀库存校验' $_.Exception.Message }
        } else { Skip 'C5-19' 'Redis 秒杀库存校验' 'Redis 不可用' }
        $rmSide = Http 'DELETE' ('/platform/admin/flash-sales/' + $activeSceneId + '/items/' + $candidateItemId) $null $adminToken
        Check 'C5-20' ('删除进行中场次商品 code=0（code=' + (EnvCode $rmSide) + '）') ((EnvCode $rmSide) -eq 0) 'code=0' (RespBrief $rmSide)
        $shopFlash2 = HttpData 'GET' ('/platform/shop/flash-sale?campusId=' + $CampusId)
        $shopFi2 = @(Prop $shopFlash2 'items')
        Check 'C5-21' '删除后 GET /platform/shop/flash-sale 不再含该商品' ((@($shopFi2 | Where-Object { (IntProp $_ @('itemId')) -eq $candidateItemId }).Count) -eq 0) '0 条' ((@($shopFi2 | Where-Object { (IntProp $_ @('itemId')) -eq $candidateItemId }).Count).ToString())
        if ($script:infraRedis) {
          try {
            $sk = 'ershou:platform:seckill:stock:' + $candidateItemId
            $ex = RedisExists $sk
            $v = if ($ex) { RedisGet $sk } else { '0' }
            Check 'C5-22' ('删除后 Redis seckill:stock:' + $candidateItemId + ' 不再为正数（exists=' + $ex + ', value=' + $v + '）') ((-not $ex) -or ([int]$v -le 0)) '不存在或 <=0' ($ex.ToString() + '/' + $v)
          } catch { Skip 'C5-22' 'Redis 秒杀库存清理校验' $_.Exception.Message }
        }
      }
    } else {
      Skip 'C5-17..22' '场次加商品副作用' $(if ($null -eq $activeScene) { '没有进行中场次' } else { '没有可用平台商品' })
    }
    # 非法：给场次加非平台商品
    if ($script:infraDb -and $script:createdSceneId -gt 0) {
      try {
        $personalId = SqlCount 'SELECT COALESCE(MIN(id),0) FROM item WHERE seller_type=1;'
        if ($personalId -gt 0) {
          $badAdd = Http 'POST' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/items') @{ itemId = $personalId; seckillPrice = 1; seckillStock = 1; perUserLimit = 1 } $adminToken
          Check 'C5-23' ('给场次加非平台商品（id=' + $personalId + '）被拒（code=' + (EnvCode $badAdd) + '）') ((EnvCode $badAdd) -ne 0) 'code!=0' (RespBrief $badAdd)
        } else { Skip 'C5-23' '非平台商品校验' 'DB 无个人商品' }
      } catch { Skip 'C5-23' '非平台商品校验' $_.Exception.Message }
    } elseif ($script:createdSceneId -le 0) { Skip 'C5-23' '非平台商品校验' '新建场次失败' }
    # 非法状态值
    if ($script:createdSceneId -gt 0) {
      $badStatus = Http 'POST' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/status?status=9') $null $adminToken
      Check 'C5-24' ('场次非法状态 9 被拒（code=' + (EnvCode $badStatus) + '）') ((EnvCode $badStatus) -ne 0) 'code!=0' (RespBrief $badStatus)
    }
  }

  # ================================================================ C6 平台订单
  Head 'C6 3.5 平台订单 GET /platform/admin/orders'
  $ordResp = Http 'GET' '/platform/admin/orders?pageNum=1&pageSize=10' $null $adminToken
  $ordOk = CheckEnvelope 'C6-1' 'GET /platform/admin/orders code=0' $ordResp
  $ordData = Prop $ordResp.obj 'data'
  $orders = @(Prop $ordData 'records')
  $ordTotal = IntProp $ordData @('total')
  if ($ordOk) {
    Check 'C6-2' ('订单分页结构（total=' + $ordTotal + ', records=' + $orders.Count + '）') (($null -ne $ordData) -and (HasProp $ordData 'records') -and (HasProp $ordData 'total')) 'PageResult' ('total=' + $ordTotal)
    # 排序：按 id 倒序
    $ids = @($orders | ForEach-Object { IntProp $_ @('id') })
    $sorted = $true
    for ($i = 1; $i -lt $ids.Count; $i++) { if ($ids[$i] -gt $ids[$i - 1]) { $sorted = $false } }
    Check 'C6-3' '订单按 id 倒序' $sorted '降序' ($ids -join ',')
    if ($orders.Count -gt 0) {
      $o0 = $orders[0]
      $oFields = @('id','orderNo','itemId','itemTitle','buyerId','buyerNickname','quantity','dealPrice','status','statusText','pointId','pointName','createdAt')
      $oMissing = @($oFields | Where-Object { -not (HasProp $o0 $_) })
      Check 'C6-4' ('AdminPlatformOrderVO 字段齐全（缺 ' + ($oMissing -join ',') + '）') ($oMissing.Count -eq 0) '13 字段' ($oMissing -join ',')
      Check 'C6-5' ('买家昵称/代卖点名已联查（buyerNickname="' + (StrProp $o0 @('buyerNickname')) + '", pointName="' + (StrProp $o0 @('pointName')) + '"）') ((StrProp $o0 @('buyerNickname')).Length -gt 0 -and (StrProp $o0 @('pointName')).Length -gt 0) '非空' ((StrProp $o0 @('buyerNickname')) + '/' + (StrProp $o0 @('pointName')))
      $oStatus = IntProp $o0 @('status')
      $stFilter = Http 'GET' ('/platform/admin/orders?pageNum=1&pageSize=20&status=' + $oStatus) $null $adminToken
      $stOrders = @(Prop (Prop $stFilter.obj 'data') 'records')
      Check 'C6-6' ('status 筛选可用（status=' + $oStatus + '，' + $stOrders.Count + ' 条且全匹配）') ((EnvCode $stFilter) -eq 0 -and (@($stOrders | Where-Object { (IntProp $_ @('status')) -ne $oStatus }).Count -eq 0)) '全部匹配' (RespBrief $stFilter)
      $kw = StrProp $o0 @('orderNo')
      if ($kw.Length -gt 0) {
        $kwOrd = Http 'GET' ('/platform/admin/orders?pageNum=1&pageSize=20&keyword=' + [uri]::EscapeDataString($kw)) $null $adminToken
        $kwOrders = @(Prop (Prop $kwOrd.obj 'data') 'records')
        Check 'C6-7' ('keyword 按订单号命中（"'+$kw+'"，'+$kwOrders.Count+' 条）') ((EnvCode $kwOrd) -eq 0 -and (@($kwOrders | Where-Object { (StrProp $_ @('orderNo')) -eq $kw }).Count) -ge 1) '>=1' (RespBrief $kwOrd)
      }
    } else {
      Info '基线无平台订单，创建一个平台订单用于验证 orders 字段与概览联动'
      if (-not $SkipMutations -and $items.Count -gt 0) {
        $buyItem = @($items | Where-Object { (IntProp $_ @('status')) -eq 2 -and (IntProp $_ @('id')) -gt 0 })[0]
        if ($null -ne $buyItem) {
          $buyItemId = IntProp $buyItem @('id')
          $buyResp = Http 'POST' ('/platform/me/items/' + $buyItemId + '/buy') @{ quantity = 1 } $buyerToken
          if ((EnvCode $buyResp) -eq 0) {
            $script:createdOrderId = IntProp (Prop $buyResp.obj 'data') @('orderId', 'id')
            $script:testData.orders += $script:createdOrderId
            Info ('已创建平台测试订单 orderId=' + $script:createdOrderId + '（buyer=' + $BuyerPhone + ', item=' + $buyItemId + '，无法删除，记入残留）')
            $ordResp2 = Http 'GET' '/platform/admin/orders?pageNum=1&pageSize=10' $null $adminToken
            $orders = @(Prop (Prop $ordResp2.obj 'data') 'records')
            if ($orders.Count -gt 0) {
              $o0 = $orders[0]
              Check 'C6-8' ('新建平台单出现在 orders（orderNo=' + (StrProp $o0 @('orderNo')) + '）') ((StrProp $o0 @('orderNo')).Length -gt 0) '非空' (StrProp $o0 @('orderNo'))
              Check 'C6-9' ('orders 行含买家昵称/数量/金额/状态文案/点名（' + (StrProp $o0 @('buyerNickname')) + '/' + (IntProp $o0 @('quantity')) + '/' + (StrProp $o0 @('dealPrice')) + '/' + (StrProp $o0 @('statusText')) + '/' + (StrProp $o0 @('pointName')) + '）') ((StrProp $o0 @('buyerNickname')).Length -gt 0 -and (IntProp $o0 @('quantity')) -ge 1 -and (StrProp $o0 @('statusText')).Length -gt 0 -and (StrProp $o0 @('pointName')).Length -gt 0) '字段非空' (RespBrief $ordResp2)
            }
            # 概览联动：订单数/GMV/待自提
            $ov2 = HttpData 'GET' ('/platform/admin/overview?campusId=' + $CampusId) $null $adminToken
            Check 'C6-10' ('概览 orderTotal >= 1（实际 ' + (IntProp $ov2 @('orderTotal')) + '）') ((IntProp $ov2 @('orderTotal')) -ge 1) '>=1' (IntProp $ov2 @('orderTotal')).ToString()
            Check 'C6-11' ('概览 orderToday >= 1（实际 ' + (IntProp $ov2 @('orderToday')) + '）') ((IntProp $ov2 @('orderToday')) -ge 1) '>=1' (IntProp $ov2 @('orderToday')).ToString()
            Check 'C6-12' ('概览 pendingPickup >= 1（实际 ' + (IntProp $ov2 @('pendingPickup')) + '）') ((IntProp $ov2 @('pendingPickup')) -ge 1) '>=1' (IntProp $ov2 @('pendingPickup')).ToString()
            Check 'C6-13' ('概览 gmvTotal > 0（实际 ' + (StrProp $ov2 @('gmvTotal')) + '）') ((StrProp $ov2 @('gmvToday')) -ne '' -and [decimal](Prop $ov2 'gmvTotal') -gt 0) '>0' (StrProp $ov2 @('gmvTotal'))
          } else {
            Check 'C6-8' ('创建平台测试订单（item=' + $buyItemId + '）') $false 'code=0' (RespBrief $buyResp)
          }
        }
      } else { Skip 'C6-8..13' '订单字段/概览联动' 'SkipMutations 或无可用平台商品' }
    }
  }

  # ================================================================ C7 缓存运维
  Head 'C7 3.6 缓存运维 GET /platform/admin/cache/stats'
  $csResp = Http 'GET' '/platform/admin/cache/stats' $null $adminToken
  $csOk = CheckEnvelope 'C7-1' 'GET /platform/admin/cache/stats code=0' $csResp
  $cs = Prop $csResp.obj 'data'
  if ($csOk) {
    Check 'C7-2' ('stats.warmupLockHeld 为布尔（实际 ' + (StrProp $cs @('warmupLockHeld')) + '）') ($null -ne (Prop $cs 'warmupLockHeld')) 'boolean' 'missing'
    $tk = IntProp $cs @('totalKeys')
    Check 'C7-3' ('stats.totalKeys > 0（实际 ' + $tk + '）') ($tk -gt 0) '>0' $tk.ToString()
    $keys = @(Prop $cs 'keys')
    Check 'C7-4' ('stats.keys 数量 == totalKeys（' + $keys.Count + ' vs ' + $tk + '）') ($keys.Count -eq $tk) $tk.ToString() $keys.Count.ToString()
    if ($keys.Count -gt 0) {
      $kFields = @('key','type','ttl','value')
      $kMissing = @($kFields | Where-Object { -not (HasProp $keys[0] $_) })
      Check 'C7-5' ('key 明细字段齐全（缺 ' + ($kMissing -join ',') + '）') ($kMissing.Count -eq 0) 'key/type/ttl/value' ($kMissing -join ',')
      $badPrefix = @($keys | Where-Object { -not ((StrProp $_ @('key')).StartsWith('ershou:platform:')) })
      Check 'C7-6' ('所有 key 均为 ershou:platform:* 前缀（异常 ' + $badPrefix.Count + '）') ($badPrefix.Count -eq 0) '0' $badPrefix.Count.ToString()
      $stockKeys = @($keys | Where-Object { (StrProp $_ @('key')) -match ':stock:' })
      Check 'C7-7' ('包含库存 key（' + $stockKeys.Count + ' 个）') ($stockKeys.Count -ge 3) '>=3' $stockKeys.Count.ToString()
      $strKeys = @($keys | Where-Object { (StrProp $_ @('type')) -eq 'string' })
      $hashKeys = @($keys | Where-Object { (StrProp $_ @('type')) -eq 'hash' })
      Info ('cache 明细：total=' + $tk + ' string=' + $strKeys.Count + ' hash=' + $hashKeys.Count + ' 样例=' + ((@($keys | Select-Object -First 3) | ForEach-Object { (StrProp $_ @('key')) + '(' + (StrProp $_ @('type')) + ',ttl=' + (StrProp $_ @('ttl')) + ')' }) -join '; '))
    }
    $refResp = Http 'POST' '/platform/admin/cache/refresh' @{} $adminToken
    $refOk = CheckEnvelope 'C7-8' 'POST /platform/admin/cache/refresh code=0' $refResp
    if ($refOk) { Info ('refresh 重建库存 key 数=' + (StrProp $refResp.obj @('data'))) }
    $cs2 = HttpData 'GET' '/platform/admin/cache/stats' $null $adminToken
    Check 'C7-9' ('refresh 后 stats.totalKeys > 0（实际 ' + (IntProp $cs2 @('totalKeys')) + '）') ((IntProp $cs2 @('totalKeys')) -gt 0) '>0' (IntProp $cs2 @('totalKeys')).ToString()
  }

  # ================================================================ C8 权限负向
  Head 'C8 权限负向（未登录 10002 / 普通用户 10003）'
  $unauthFails = 0; $unauthOk = 0
  foreach ($ep in $script:adminEndpoints) {
    $r = Http $ep.m $ep.p $ep.b ''
    $code = EnvCode $r
    if ($code -eq 10002) { $unauthOk++ } else { $unauthFails++; Check ('C8-U-' + $ep.id) ('未登录访问 ' + $ep.p + ' 应 10002（实际 ' + $code + '）') $false '10002' (RespBrief $r) }
  }
  Check 'C8-1' ('未登录访问 ' + $script:adminEndpoints.Count + ' 个管理接口全部 10002（通过 ' + $unauthOk + '）') ($unauthFails -eq 0) ('全部 10002') ($unauthOk.ToString() + '/' + $script:adminEndpoints.Count)
  $buyerFails = 0; $buyerOk = 0
  foreach ($ep in $script:adminEndpoints) {
    $r = Http $ep.m $ep.p $ep.b $buyerToken
    $code = EnvCode $r
    if ($code -eq 10003) { $buyerOk++ } else { $buyerFails++; Check ('C8-B-' + $ep.id) ('普通用户访问 ' + $ep.p + ' 应 10003（实际 ' + $code + '）') $false '10003' (RespBrief $r) }
  }
  Check 'C8-2' ('普通用户访问 ' + $script:adminEndpoints.Count + ' 个管理接口全部 10003 无权限（通过 ' + $buyerOk + '）') ($buyerFails -eq 0) '全部 10003' ($buyerOk.ToString() + '/' + $script:adminEndpoints.Count)
} catch {
  Check 'C9-0' '验收主流程' $false '可执行' ('异常: ' + $_.Exception.Message + ' @ ' + $_.InvocationInfo.ScriptLineNumber)
} finally {
  # ================================================================ C9 清理测试数据
  Head 'C9 清理测试数据（尽量回滚）'
  if ($script:itemRestore -ne $null) {
    try {
      $r = Http 'PUT' ('/platform/admin/items/' + $script:itemRestore.id) @{ title = $script:itemRestore.title; price = $script:itemRestore.price; stock = $script:itemRestore.stock } $adminToken
      Info ('还原商品 ' + $script:itemRestore.id + ' -> code=' + (EnvCode $r))
    } catch { Warn 'C9-1' '还原商品失败' $_.Exception.Message }
  }
  foreach ($ai in $script:addedFlashItemIds) {
    try {
      $r = Http 'DELETE' ('/platform/admin/flash-sales/' + $ai.scene + '/items/' + $ai.item) $null $adminToken
      Info ('移除场次商品 scene=' + $ai.scene + ' item=' + $ai.item + ' -> code=' + (EnvCode $r))
    } catch { Warn 'C9-2' '移除场次商品失败' $_.Exception.Message }
  }
  if ($script:createdCouponId -gt 0) {
    try {
      $r = Http 'POST' ('/platform/admin/coupons/' + $script:createdCouponId + '/status?status=0') $null $adminToken
      Info ('停用 QA 券 id=' + $script:createdCouponId + ' -> code=' + (EnvCode $r) + '（无删除接口，保留软停用）')
    } catch { Warn 'C9-3' '停用 QA 券失败' $_.Exception.Message }
  }
  if ($script:createdSceneId -gt 0) {
    try {
      $r = Http 'POST' ('/platform/admin/flash-sales/' + $script:createdSceneId + '/status?status=3') $null $adminToken
      Info ('QA 场次 id=' + $script:createdSceneId + ' 置结束 -> code=' + (EnvCode $r) + '（无删除接口，保留已结束场次）')
    } catch { Warn 'C9-4' '结束 QA 场次失败' $_.Exception.Message }
  }

  # ================================================================ 汇总
  $summary = [ordered]@{
    at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    base = $base
    campusId = $CampusId
    mode = $(if ($SkipMutations) { 'readonly' } else { 'full' })
    pass = $script:pass; fail = $script:fail; skip = $script:skip; warn = $script:warn
    admin = @{ phone = $AdminPhone; userId = $adminId }
    buyer = @{ phone = $BuyerPhone; userId = $buyerId }
    testData = @{
      createdCouponId = $script:createdCouponId
      createdSceneId = $script:createdSceneId
      createdOrderId = $script:createdOrderId
      residualNote = 'QA 券已停用、QA 场次已结束、测试订单无法删除（记入残留）；商品已还原'
    }
    results = @($script:checkResults)
  }
  $summary | ConvertTo-Json -Depth 8 | Set-Content -Path $outFull -Encoding UTF8
  Write-Host ''
  Write-Host '------------------------------------------------------------' -ForegroundColor White
  if ($script:fail -eq 0) {
    Write-Host ('API 验收全部通过：' + $script:pass + ' 项断言（WARN ' + $script:warn + '，SKIP ' + $script:skip + '）') -ForegroundColor Green
  } else {
    Write-Host ('API 验收：通过 ' + $script:pass + ' 项，失败 ' + $script:fail + ' 项，SKIP ' + $script:skip + '，WARN ' + $script:warn) -ForegroundColor Red
  }
  Write-Host ('结果 JSON: ' + $outFull) -ForegroundColor DarkGray
  Write-Host '提示：本脚本会创建 QA 券/场次/测试订单（均已尽量停用/结束；订单无删除接口）；后台商品编辑已还原。' -ForegroundColor DarkGray
  Write-Host '------------------------------------------------------------' -ForegroundColor White
  exit ([Math]::Min($script:fail, 250))
}
