<#
.SYNOPSIS
  第 2 轮「平台代卖」验收 · API 端到端脚本（HTTP 契约层，不改库、不改源码）。

.DESCRIPTION
  对应 `智能体团队/第2轮-平台代卖-设计.md`：
    - 第三节 3.2 公开读接口 / 3.3 登录用户动作 / 3.4 管理员接口 / 3.5 platformBuy
    - 第七节验收标准 4（运行时断言）、6（Web 由其它脚本负责）、7（回归）

  断言分组（ID 前缀 = 设计条款）：
    A1  环境：后端可用、-DryRun 计划
    A2  3.2 公开读：overview / points / items / coupons / flash-sale 字段与状态
    A3  验收 4：GET /items?pageNum=1 中平台商品 sellerType=2
    A4  3.3 领券单次：成功 + DB platform_coupon_user +1 + Redis stock -1；重复领取 => data.code=-2
    A5  3.3 领券并发 Concurrency 次：成功数 == min(并发数, 库存)，Redis/DB 库存不为负
    A6  3.3 抢购（v1.1 不锁库只扣库存）：单用户成功 => meetup_order(status=2,seller_id=1000,point_id!=null)+platform_seckill_record+Redis stock-1+item.stock-1+lock 字段 NULL；
        A6.2 并发：库存 N => N 个不同用户全成功、第 N+1 个失败（Redis/DB 两平面分别断言）；同用户再抢 => 超限/已抢完
    A7  3.3 直购（v1.1）：建单成功、item.stock-1（不锁库、status=(stock>0?2:4)）、买家 /meetup-orders 可见；
        完成流程：买家 confirm => 单 status=3、item 按剩余库存收口、买家信用 +2、平台账号 0 credit_log；-TestCancelRestore 时验证取消回补库存
    A8  3.4 负向：普通用户访问 /platform/admin/** 必须被拒（10003）
    A9  3.4 正向：管理员点位 CRUD（新增/编辑/启停/软删）+ 新增点位出现在 shop/points
    A10 3.4 缓存重建：POST /admin/cache/refresh 后 Redis 平台 key 存在
    A11 回归：登录/首页/详情/购物车/面交单/分类/附近点位/未读 接口 code=0
    A12 汇总：结果 JSON + 退出码

  前置：
    1) 后端已在 8080 运行（`mvn -DskipTests spring-boot:run` 或 `java -jar`；**8080 只能一个实例**）
    2) MySQL / Redis / ES 已启动；Lead 已执行 sql/migration-platform.sql
    3) 管理员权限：Lead 用 SQL 授予测试账号（脚本会打印需要的 SQL），例如
         UPDATE sys_user SET role_flags = role_flags | 8 WHERE id = <管理员测试账号 id>;

  用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-api.ps1 -DryRun      # 只看计划
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-api.ps1              # 跑全部
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-api.ps1 -SkipMutations # 只跑只读断言
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-api.ps1 -AdminOnly    # 只补跑管理员组（A9/A10）
        授权流程：首跑全量时 A9-0 会失败并打印「管理员 phone / userId / 需要的 UPDATE SQL」；
                  由 Lead 执行 `UPDATE sys_user SET role_flags = role_flags | 8 WHERE id = <id>;` 后，
                  用 -AdminOnly 补跑（跳过 A4~A7，不再消耗券/秒杀库存）。

  注意：本脚本会**真实创建测试数据**（领券/抢购/直购会消耗库存并生成面交单；管理员用例会建一个 QA 点位再软删）。
    - 每轮跑会创建约 Concurrency+3 个测试用户（手机号见输出），并消耗最多 Concurrency 张券库存；
    - 券/秒杀库存耗尽时相关用例会 SKIP 并提示需重置种子（本脚本不自己改库）。

.NOTES
  退出码：0=全通过；2=后端不可用；>0=失败断言数。
#>
param(
  [string]$Base = 'http://127.0.0.1:8080',
  [string]$ApiPrefix = '/api/v1',
  [int]$CampusId = 2,
  [int]$Concurrency = 10,
  [string]$BuyerPhone = '19900000101',
  [string]$AdminPhone = '19900000102',
  [string]$GrabPhone = '19900000192',
  [string]$BuyPhone = '19900000193',
  [string]$SmsCode = '123456',
  [int]$CouponId = 0,
  [int]$ConcurrencyCouponId = 0,
  [int]$GrabItemId = 0,
  [int]$BuyItemId = 0,
  [string]$AdminNewPointName = 'QA自动化代卖点',
  [switch]$SkipMutations,
  [switch]$SkipInfra,
  [switch]$SkipAdmin,
  [switch]$AdminOnly,
  [switch]$TestCancelRestore,
  [switch]$DryRun,
  [switch]$VerboseBody,
  [string]$TokenCache = '.build\qa\platform-qa-tokens.json',
  [string]$OutJson = ''
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$qaDir = Join-Path $root '.build\qa'
if (-not (Test-Path $qaDir)) { New-Item -ItemType Directory -Force -Path $qaDir | Out-Null }

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
$outFull = if ($OutJson) { if ([System.IO.Path]::IsPathRooted($OutJson)) { $OutJson } else { Join-Path $root $OutJson } } else { Join-Path $qaDir 'platform-api-result.json' }
$tokenFile = if ($TokenCache) { if ([System.IO.Path]::IsPathRooted($TokenCache)) { $TokenCache } else { Join-Path $root $TokenCache } } else { '' }

# -AdminOnly = 只补跑管理员组（A9/A10）：必须自动视为 SkipMutations，
# 否则 A4~A7 的写操作会重复执行、重复消耗券/秒杀库存（踩过：参数忘了带 -SkipMutations）。
if ($AdminOnly) {
  $SkipMutations = $true
  Write-Host '  [INFO] -AdminOnly：跳过 A4~A7 写操作，只跑管理员组 A9/A10' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 结果收集
# 注意：这些变量名是脚本级（等价 $script:）。不要用 $results / $evidence 这类
# 会被各断言块局部数组覆盖的名字 —— 覆盖后 Check() 的 .Add() 会抛 "Collection was of a fixed size"。
$script:pass = 0; $script:fail = 0; $script:skip = 0; $script:warn = 0
$script:checkResults = New-Object System.Collections.ArrayList
$script:checkEvidence = New-Object System.Collections.ArrayList
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
function Info([string]$Text) { Write-Host ('  [INFO] ' + $Text) -ForegroundColor DarkGray; $null = $script:checkEvidence.Add($Text) }
function Head([string]$Text) { Write-Host ''; Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan }

# ---------------------------------------------------------------- HTTP 客户端（保留非 2xx 的 body，便于取证）
function Http([string]$method, [string]$path, $body = $null, [string]$token = '', [int]$timeoutSec = 20) {
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
      try {
        $stream = $we.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $raw = $reader.ReadToEnd()
      } catch { $raw = '<读取错误响应体失败>' }
    } else {
      return [pscustomobject]@{ status = 0; raw = ('网络异常: ' + $we.Message); obj = $null; path = $path }
    }
  }
  $obj = $null
  if ($raw -and $raw.TrimStart().StartsWith('{')) {
    try { $obj = $raw | ConvertFrom-Json } catch { $obj = $null }
  }
  if ($VerboseBody) { Info ($method + ' ' + $path + ' -> HTTP ' + $status + ' ' + $(if ($raw.Length -gt 600) { $raw.Substring(0,600) + '...' } else { $raw })) }
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
  $b = if ($resp.raw.Length -gt 500) { $resp.raw.Substring(0,500) + '...' } else { $resp.raw }
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

# ---------------------------------------------------------------- infra 交叉校验（DB / Redis）
$script:mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$script:infraDb = (-not $SkipInfra) -and (Test-Path $script:mysql)
$script:infraRedis = (-not $SkipInfra)
$script:dockerOk = $false
if ($script:infraRedis) {
  try { $null = & docker version --format '{{.Server.Version}}' 2>$null; $script:dockerOk = ($LASTEXITCODE -eq 0) } catch { $script:dockerOk = $false }
  $script:infraRedis = $script:dockerOk
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
  # 用 stdin 传 SQL：PowerShell 5.1 把 `-e '... CONCAT_WS("|", ...)'` 传给原生 mysql.exe 时
  # 双引号会被吞掉 → MySQL 报 1064 语法错误（踩过：A6/A7/A9 的 DB 交叉校验整组失效）
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
function RedisGet([string]$key)  { return RedisOne @('GET', $key) }
function RedisExists([string]$key) { return (RedisOne @('EXISTS', $key)) -eq '1' }
function RedisHashVal([string]$key, [string]$field) { return RedisOne @('HGET', $key, $field) }
# 多 key EXISTS 一次问完：返回存在的个数（-1=Redis 不可用）；比逐个 EXISTS 更稳、也能给缺失明细
function RedisExistCount([string[]]$keys) {
  $a = @('exec', 'ershou-redis', 'redis-cli', '-a', $env:REDIS_PASSWORD, '--no-auth-warning', '-n', $(if ($env:REDIS_DATABASE) { $env:REDIS_DATABASE } else { '0' })) + @('EXISTS') + $keys
  $out = & docker @a 2>$null
  if ($LASTEXITCODE -ne 0) { return -1 }
  $v = (@($out) | Select-Object -First 1)
  if ($null -eq $v) { return -1 }
  $v = ([string]$v).Trim()
  if ($v -match '^\d+$') { return [int]$v }
  return -1
}
# 返回多行结果的全部行（--scan / SMEMBERS 这类），RedisOne 只取第一行会漏
function RedisLines([string[]]$argv) {
  $a = @('exec', 'ershou-redis', 'redis-cli', '-a', $env:REDIS_PASSWORD, '--no-auth-warning', '-n', $(if ($env:REDIS_DATABASE) { $env:REDIS_DATABASE } else { '0' })) + $argv
  $out = & docker @a 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('docker exec redis-cli 失败：' + ($argv -join ' ')) }
  return @(@($out) | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
}
function CouponStock([int]$id) { return RedisGet ('ershou:platform:coupon:stock:' + $id) }
function SeckillStock([int]$id) { return RedisGet ('ershou:platform:seckill:stock:' + $id) }

# ---------------------------------------------------------------- 登录（mock 验证码；token 缓存避免短信每日上限）
# 注意：变量名不要用 $tokenCache —— 会和 [string]$TokenCache 参数同名（PowerShell 变量名大小写不敏感，
# 且参数带 [string] 类型约束），赋值 @{} 会被强制转成字符串，.ContainsKey 直接报错。
$script:tokenStore = @{}
if ($tokenFile -and (Test-Path $tokenFile)) {
  try {
    $cached = Get-Content -Raw -Encoding UTF8 $tokenFile | ConvertFrom-Json
    foreach ($p in $cached.PSObject.Properties) { $script:tokenStore[$p.Name] = [string]$p.Value.token }
  } catch { }
}
function Save-TokenCache {
  if (-not $tokenFile) { return }
  $obj = [ordered]@{}
  foreach ($k in $script:tokenStore.Keys) { $obj[$k] = @{ token = $script:tokenStore[$k]; at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') } }
  $obj | ConvertTo-Json -Depth 4 | Set-Content -Path $tokenFile -Encoding UTF8
}
function Login([string]$phone) {
  # 1) 先试缓存 token（token 与地址无关；10 年有效，省短信条数）
  if ($script:tokenStore.ContainsKey($phone)) {
    $t = $script:tokenStore[$phone]
    $me = Http 'GET' '/users/me' $null $t 8
    if ((EnvCode $me) -eq 0) {
      return [pscustomobject]@{ token=$t; user=(Prop $me.obj 'data'); phone=$phone; cached=$true }
    }
  }
  # 2) mock 验证码登录
  $r1 = Http 'POST' '/auth/sms-code' @{ phone = $phone }
  if ((EnvCode $r1) -ne 0) { throw ('发验证码失败 phone=' + $phone + '：' + (RespBrief $r1)) }
  $r2 = Http 'POST' '/auth/login' @{ phone = $phone; code = $SmsCode; schoolId = 1; campusId = $CampusId; deviceId = ('qa-' + $phone) }
  if ((EnvCode $r2) -ne 0) { throw ('登录失败 phone=' + $phone + '：' + (RespBrief $r2)) }
  $token = StrProp (Prop $r2.obj 'data') @('token')
  if (-not $token) { throw ('登录响应里没有 token：' + (RespBrief $r2)) }
  $script:tokenStore[$phone] = $token
  Save-TokenCache
  return [pscustomobject]@{ token=$token; user=(Prop (Prop $r2.obj 'data') 'user'); phone=$phone; cached=$false }
}

# ---------------------------------------------------------------- 并发 POST（HttpClient，真并发）
function ConcurrentPost([string]$path, [string[]]$tokens, $body) {
  Add-Type -AssemblyName System.Net.Http | Out-Null
  [System.Net.ServicePointManager]::DefaultConnectionLimit = 100
  $client = [System.Net.Http.HttpClient]::new()
  $client.Timeout = [TimeSpan]::FromSeconds(30)
  $json = $body | ConvertTo-Json -Depth 6 -Compress
  $tasks = New-Object 'System.Collections.Generic.List[System.Threading.Tasks.Task]'
  foreach ($t in $tokens) {
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, ($base + $path))
    $null = $req.Headers.TryAddWithoutValidation('Authorization', 'Bearer ' + $t)
    $null = $req.Headers.TryAddWithoutValidation('Accept', 'application/json')
    $req.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
    $tasks.Add($client.SendAsync($req))
  }
  try { [System.Threading.Tasks.Task]::WaitAll($tasks.ToArray()) } catch { $client.Dispose(); throw }
  $out = @()
  foreach ($task in $tasks) {
    $resp = $task.Result
    $text = $resp.Content.ReadAsStringAsync().Result
    $obj = $null
    if ($text -and $text.TrimStart().StartsWith('{')) { try { $obj = $text | ConvertFrom-Json } catch { } }
    $out += ,([pscustomobject]@{ status = [int]$resp.StatusCode; raw = $text; obj = $obj })
  }
  $client.Dispose()
  return $out
}

# ---------------------------------------------------------------- 预检
Write-Host '============================================================' -ForegroundColor White
Write-Host ' 第 2 轮 · 平台代卖验收 · API 端到端' -ForegroundColor White
Write-Host (' 后端: ' + $base + '  校区: ' + $CampusId + '  并发: ' + $Concurrency) -ForegroundColor DarkGray
Write-Host (' 时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  模式: ' + $(if ($DryRun) { 'DryRun' } elseif ($SkipMutations) { '只读' } else { '全量(含写操作)' })) -ForegroundColor DarkGray
Write-Host '============================================================' -ForegroundColor White

Head 'A1 环境'
if ($DryRun) {
  Write-Host ' 计划执行的断言分组（不实际请求）：' -ForegroundColor Cyan
  @(
    'A2  3.2 公开读：overview / points / items / coupons / flash-sale',
    'A3  验收 4：GET /items?pageNum=1 平台商品 sellerType=2',
    'A4  3.3 领券：首次成功(data.code=1) + DB platform_coupon_user +1 + Redis stock -1；重复领取 data.code=-2',
    ('A5  3.3 领券并发 ' + $Concurrency + ' 个不同用户：成功数 == min(' + $Concurrency + ', 库存)，库存不为负（Redis/DB 双查）'),
    'A6  3.3 抢购（v1.1 不锁库只扣库存）：单用户成功 => meetup_order(status=2,seller_id=1000,point_id!=null)+seckill_record+Redis-1+item.stock-1+lock 字段 NULL；同用户再抢超限',
    'A6.2 并发抢购：库存 N => N 个不同用户全成功、第 N+1 个失败（Redis 平面与 item.stock DB 平面分别断言）',
    'A7  3.3 直购（v1.1）：建单+item.stock-1（不锁库、status=(stock>0?2:4)）+ 买家面交单可见；买家 confirm => status=3、商品按剩余库存收口、买家+2、平台账号 0 流水',
    'A8  3.4 负向：普通用户访问 /platform/admin/** => 10003 无权限',
    'A9  3.4 正向：管理员点位新增/编辑/启停/软删，新增点位出现在 shop/points',
    'A10 3.4 缓存重建：/admin/cache/refresh 后 ershou:platform:* key 存在',
    'A11 回归：categories/items/detail/cart/meetup-orders/trade-points/chat unread 全部 code=0',
    'A12 汇总：结果 JSON + 退出码'
  ) | ForEach-Object { Write-Host ('   - ' + $_) -ForegroundColor DarkGray }
  Write-Host ''
  Write-Host '  测试账号（固定，token 缓存在 .build/qa/platform-qa-tokens.json）：' -ForegroundColor DarkGray
  Write-Host ('   买家=' + $BuyerPhone + '  管理员=' + $AdminPhone + '  抢购=' + $GrabPhone + '  直购=' + $BuyPhone) -ForegroundColor DarkGray
  Write-Host ('   并发用户 ' + $Concurrency + ' 个：每轮新建（手机号 199+ddHHmm+序号），会消耗最多 ' + $Concurrency + ' 张券库存') -ForegroundColor DarkGray
  Write-Host ('  管理员授权 SQL（Lead 执行）：UPDATE sys_user SET role_flags = role_flags | 8 WHERE id = <' + $AdminPhone + ' 对应的 user id>;') -ForegroundColor DarkGray
  Write-Host '  授权后补跑管理员组：加 -AdminOnly（只跑 A9/A10，跳过 A4~A7，不重复消耗券/秒杀库存）' -ForegroundColor DarkGray
  exit 0
}

$probe = Http 'GET' '/categories' $null '' 8
if ((EnvCode $probe) -ne 0) {
  Write-Host ('[FAIL] A1-1 后端不可用：GET /categories => ' + (RespBrief $probe)) -ForegroundColor Red
  Write-Host '  请先启动后端（8080 只能一个实例）后再跑本脚本。' -ForegroundColor Yellow
  exit 2
}
Check 'A1-1' '后端可用（GET /categories code=0）' $true 'code=0' 'ok'
Warn 'A1-2' 'infra 交叉校验' ('MySQL=' + $(if ($script:infraDb) { '开' } else { '关' }) + ', Redis=' + $(if ($script:infraRedis) { '开' } else { '关' }) + '（-SkipInfra 可关）')

# 登录
Head 'A1.5 登录（mock 验证码 ' + $SmsCode + '）'
try {
  $buyer = Login $BuyerPhone
  $buyerId = IntProp $buyer.user @('id')
  Check 'A1-3' ('买家登录成功 phone=' + $BuyerPhone + ' userId=' + $buyerId + $(if ($buyer.cached) { '（token 缓存命中）' } else { '（新登录）' })) ($buyerId -gt 0) 'userId>0' ($buyerId.ToString())
  Info ('买家 token 前 8 位=' + $buyer.token.Substring(0, [Math]::Min(8, $buyer.token.Length)) + '...')
} catch {
  Check 'A1-3' '买家登录' $false '登录成功' $_.Exception.Message
  Write-Host '买家登录失败，后续断言无法进行。' -ForegroundColor Red
  exit 3
}

# ================================================================ A2 3.2 公开读接口
Head 'A2 设计 3.2 公开读接口'
$overview = Http 'GET' ('/platform/shop/overview?campusId=' + $CampusId)
$overviewOk = CheckEnvelope 'A2-1' 'GET /platform/shop/overview 200/code=0' $overview
$ov = Prop $overview.obj 'data'
if ($overviewOk) {
  $ovItemCount = IntProp $ov @('platformItemCount')
  $ovPointCount = IntProp $ov @('pointCount')
  $ovCouponCount = IntProp $ov @('couponCount')
  $ovFlashId = IntProp $ov @('flashSaleId')
  $ovFlashStatus = IntProp $ov @('flashSaleStatus')
  Check 'A2-2' ('overview.platformItemCount >= 4（实际 ' + $ovItemCount + '）') ($ovItemCount -ge 4) '>=4' $ovItemCount.ToString()
  Check 'A2-3' ('overview.pointCount >= 3（实际 ' + $ovPointCount + '）') ($ovPointCount -ge 3) '>=3' $ovPointCount.ToString()
  Check 'A2-4' ('overview.couponCount >= 3（实际 ' + $ovCouponCount + '）') ($ovCouponCount -ge 3) '>=3' $ovCouponCount.ToString()
  Check 'A2-5' ('overview.flashSaleId 非空（实际 ' + $ovFlashId + '）') ($ovFlashId -gt 0) '>0' $ovFlashId.ToString()
  Check 'A2-6' ('overview.flashSaleStatus=2 进行中（实际 ' + $ovFlashStatus + '）') ($ovFlashStatus -eq 2) '2' $ovFlashStatus.ToString()
}

$pointsResp = Http 'GET' ('/platform/shop/points?campusId=' + $CampusId)
$pointsOk = CheckEnvelope 'A2-7' 'GET /platform/shop/points 200/code=0' $pointsResp
$points = @(Prop $pointsResp.obj 'data')
if ($pointsOk) {
  Check 'A2-8' ('shop/points >= 3 条（实际 ' + $points.Count + '）') ($points.Count -ge 3) '>=3' $points.Count.ToString()
  $bad = @($points | Where-Object { (IntProp $_ @('status')) -ne 1 })
  Check 'A2-9' 'shop/points 只返回 status=1（启用）' ($bad.Count -eq 0) '0 条非启用' ($bad.Count.ToString() + ' 条非启用')
  $badCampus = @($points | Where-Object { (IntProp $_ @('campusId')) -ne $CampusId })
  Check 'A2-10' ('shop/points 全部 campusId=' + $CampusId) ($badCampus.Count -eq 0) '0 条跨校区' ($badCampus.Count.ToString() + ' 条跨校区')
  $name0 = StrProp $points[0] @('name')
  Check 'A2-11' ('shop/points 首条含 name（' + $name0 + '）') ($name0.Length -gt 0) 'name 非空' $name0
}

$itemsResp = Http 'GET' ('/platform/shop/items?pageNum=1&pageSize=12&campusId=' + $CampusId)
$itemsOk = CheckEnvelope 'A2-12' 'GET /platform/shop/items 200/code=0' $itemsResp
$itemsData = Prop $itemsResp.obj 'data'
$items = @(Prop $itemsData 'records')
if ($itemsOk) {
  $itemTotal = IntProp $itemsData @('total')
  Check 'A2-13' ('shop/items 平台商品 >= 4 条（records=' + $items.Count + ', total=' + $itemTotal + '）') (($items.Count -ge 4) -or ($itemTotal -ge 4)) 'records>=4 或 total>=4' ('records=' + $items.Count + ',total=' + $itemTotal)
  $badSeller = @($items | Where-Object { (IntProp $_ @('sellerType')) -ne 2 })
  Check 'A2-14' 'shop/items 全部 sellerType=2' ($badSeller.Count -eq 0) '0 条异常' ($badSeller.Count.ToString() + ' 条异常')
  if ($items.Count -gt 0) {
    $it = $items[0]
    Check 'A2-15' 'PlatformItemVO 必填字段齐全（id/title/price/status/pointId/pointName）' `
      ((IntProp $it @('id')) -gt 0 -and (StrProp $it @('title')).Length -gt 0 -and $null -ne (Prop $it 'price') -and (IntProp $it @('status')) -eq 2 -and (IntProp $it @('pointId')) -gt 0 -and (StrProp $it @('pointName')).Length -gt 0) `
      'id>0,title非空,price非空,status=2,pointId>0,pointName非空' (RespBrief ([pscustomobject]@{ raw = ($it | ConvertTo-Json -Depth 4 -Compress); status = 200 }))
  }
}

$couponsResp = Http 'GET' ('/platform/shop/coupons?campusId=' + $CampusId)
$couponsOk = CheckEnvelope 'A2-16' 'GET /platform/shop/coupons 200/code=0' $couponsResp
$coupons = @(Prop $couponsResp.obj 'data')
if ($couponsOk) {
  Check 'A2-17' ('shop/coupons >= 3 张（实际 ' + $coupons.Count + '）') ($coupons.Count -ge 3) '>=3' $coupons.Count.ToString()
  $badLeft = @($coupons | Where-Object { $null -eq (Prop $_ 'leftStock') -or [int](Prop $_ 'leftStock') -lt 0 })
  Check 'A2-18' '每张券都含 leftStock 且 >=0（设计 3.2 要求实时读 Redis）' ($badLeft.Count -eq 0) '全部含 leftStock>=0' ($badLeft.Count.ToString() + ' 张异常')
  $noFlag = @($coupons | Where-Object { $null -eq (Prop $_ 'claimed') -or $null -eq (Prop $_ 'claimable') })
  Check 'A2-19' '每张券都含 claimed / claimable 布尔字段' ($noFlag.Count -eq 0) '全部含' ($noFlag.Count.ToString() + ' 张缺字段')
  $sumLeft = 0; foreach ($c in $coupons) { $sumLeft += [int](Prop $c 'leftStock') }
  Info ('券库存合计 leftStock=' + $sumLeft)
  foreach ($c in $coupons) { Info ('券 id=' + (IntProp $c @('id')) + ' name=' + (StrProp $c @('name')) + ' type=' + (IntProp $c @('type')) + ' threshold=' + (StrProp $c @('thresholdAmount')) + ' leftStock=' + (StrProp $c @('leftStock')) + ' claimable=' + (StrProp $c @('claimable'))) }
}

$flashResp = Http 'GET' ('/platform/shop/flash-sale?campusId=' + $CampusId)
$flashOk = CheckEnvelope 'A2-20' 'GET /platform/shop/flash-sale 200/code=0' $flashResp
$flash = Prop $flashResp.obj 'data'
if ($flashOk) {
  Check 'A2-21' '存在进行中的秒杀场次（非 null）' ($null -ne $flash) '非 null' 'null'
  if ($null -ne $flash) {
    Check 'A2-22' 'FlashSaleVO 字段（id/title/status/remainSeconds/items）' `
      ((IntProp $flash @('id')) -gt 0 -and (StrProp $flash @('title')).Length -gt 0 -and $null -ne (Prop $flash 'status') -and $null -ne (Prop $flash 'remainSeconds') -and $null -ne (Prop $flash 'items')) `
      'id>0,title非空,status,remainSeconds,items 均存在' (RespBrief ([pscustomobject]@{ raw = ($flash | ConvertTo-Json -Depth 4 -Compress); status = 200 }))
    $flashItems = @(Prop $flash 'items')
    Check 'A2-23' ('秒杀场次商品 >= 2（实际 ' + $flashItems.Count + '）') ($flashItems.Count -ge 2) '>=2' $flashItems.Count.ToString()
    if ($flashItems.Count -gt 0) {
      $fi = $flashItems[0]
      Check 'A2-24' 'FlashSaleItemVO 字段（itemId/seckillPrice/stock/leftStock/soldCount/perUserLimit/grabbed）' `
        ((IntProp $fi @('itemId')) -gt 0 -and $null -ne (Prop $fi 'seckillPrice') -and $null -ne (Prop $fi 'stock') -and $null -ne (Prop $fi 'leftStock') -and $null -ne (Prop $fi 'soldCount') -and $null -ne (Prop $fi 'perUserLimit') -and $null -ne (Prop $fi 'grabbed')) `
        '全部字段存在' ($fi | ConvertTo-Json -Depth 3 -Compress)
      foreach ($f in $flashItems) { Info ('秒杀商品 itemId=' + (IntProp $f @('itemId')) + ' seckillPrice=' + (StrProp $f @('seckillPrice')) + ' leftStock=' + (StrProp $f @('leftStock')) + ' perUserLimit=' + (StrProp $f @('perUserLimit'))) }
    }
  }
}

# ================================================================ A3 首页数据源 sellerType
Head 'A3 验收 4：GET /items?pageNum=1 平台商品 sellerType=2'
$homeResp = Http 'GET' '/items?pageNum=1&pageSize=50'
$homeOk = CheckEnvelope 'A3-1' 'GET /items?pageNum=1 200/code=0' $homeResp
if ($homeOk) {
  $homeRecords = @(Prop (Prop $homeResp.obj 'data') 'records')
  $homePlatform = @($homeRecords | Where-Object { (IntProp $_ @('sellerType')) -eq 2 })
  Check 'A3-2' ('首页列表里出现平台商品 sellerType=2（实际 ' + $homePlatform.Count + ' 条）') ($homePlatform.Count -ge 4) '>=4' $homePlatform.Count.ToString()
  $badPointIds = @($homePlatform | Where-Object { (IntProp $_ @('platformPointId')) -le 0 })
  Check 'A3-3' '首页平台商品 platformPointId 非空（前端据 sellerType 渲染角标）' ($badPointIds.Count -eq 0) '0 条异常' ($badPointIds.Count.ToString() + ' 条异常')
  $personal = @($homeRecords | Where-Object { (IntProp $_ @('sellerType')) -ne 2 })
  if ($personal.Count -gt 0) {
    $badPersonal = @($personal | Where-Object { (IntProp $_ @('sellerType')) -ne 1 })
    Check 'A3-4' ('个人商品 sellerType=1（实际个人 ' + $personal.Count + ' 条，异常 ' + $badPersonal.Count + '）') ($badPersonal.Count -eq 0) '0 条异常' ($badPersonal.Count.ToString() + ' 条无 sellerType')
  } else { Skip 'A3-4' '个人商品 sellerType=1' '首页当前没有个人商品可对比' }
}

# ================================================================ 辅助：选券 / 选商品
function PickCoupon([int]$preferId, [string[]]$excludeIds, [int]$minStock) {
  $list = @()
  foreach ($c in $coupons) {
    $cid = IntProp $c @('id')
    if ($cid -le 0) { continue }
    if ($preferId -gt 0 -and $cid -ne $preferId) { continue }
    if ($excludeIds -contains [string]$cid) { continue }
    $claim = Prop $c 'claimable'
    $left = IntProp $c @('leftStock')
    if ($claim -eq $false) { continue }
    if ($left -lt $minStock) { continue }
    $list += ,$c
  }
  if ($list.Count -eq 0) { return $null }
  $list = @($list | Sort-Object { -1 * (IntProp $_ @('leftStock')) })
  return $list[0]
}
function PickFlashItem([int]$preferId, [int[]]$excludeIds, [int]$minLeft) {
  if ($null -eq $flash) { return $null }
  $list = @()
  foreach ($f in @(Prop $flash 'items')) {
    $iid = IntProp $f @('itemId')
    if ($iid -le 0) { continue }
    if ($preferId -gt 0 -and $iid -ne $preferId) { continue }
    if ($excludeIds -contains $iid) { continue }
    if ((IntProp $f @('leftStock')) -lt $minLeft) { continue }
    if ($null -ne (Prop $f 'grabbed') -and (Prop $f 'grabbed') -eq $true) { continue }
    $list += ,$f
  }
  if ($list.Count -eq 0) { return $null }
  return $list[0]
}

# ================================================================ A4 领券单次 + 重复
Head 'A4 设计 3.3 领券（单次 + 重复）'
$script:createdPointId = 0
$claimedCouponId = 0
if ($SkipMutations) {
  Skip 'A4' '领券写操作' '传了 -SkipMutations'
} else {
  $claimedIds = @()
  if ($script:infraDb) {
    try {
      $rows = @(& $script:mysql @script:mysqlArgs -e ('SELECT coupon_id FROM platform_coupon_user WHERE user_id=' + $buyerId + ';') 2>$null)
      $claimedIds = @($rows | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    } catch { Warn 'A4-0' '读取买家已领券失败' $_.Exception.Message }
  }
  $pick = PickCoupon $CouponId $claimedIds 1
  if ($null -eq $pick) {
    Skip 'A4' '领券单次' ('没有可领的券（已领=' + ($claimedIds -join ',') + '；可用 -CouponId 指定或换 -BuyerPhone）')
  } else {
    $claimedCouponId = IntProp $pick @('id')
    $stockBefore = -1
    if ($script:infraRedis) { try { $stockBefore = [int](CouponStock $claimedCouponId) } catch { Warn 'A4-1' '读 Redis 券库存失败' $_.Exception.Message } }
    $dbBefore = -1
    if ($script:infraDb) { try { $dbBefore = SqlCount ('SELECT COUNT(*) FROM platform_coupon_user WHERE coupon_id=' + $claimedCouponId + ';') } catch { } }
    $claim = Http 'POST' ('/platform/me/coupons/' + $claimedCouponId + '/claim') @{} $buyer.token
    $claimOk = CheckEnvelope 'A4-1' ('POST /platform/me/coupons/' + $claimedCouponId + '/claim code=0') $claim
    $claimData = Prop $claim.obj 'data'
    if ($claimOk) {
      $innerCode = IntProp $claimData @('code')
      Check 'A4-2' ('领券结果 data.code=1（成功），实际 ' + $innerCode) ($innerCode -eq 1) '1' $innerCode.ToString()
      $leftAfterResp = IntProp $claimData @('leftStock')
      if ($stockBefore -ge 0) {
        Check 'A4-3' ('领券后 Redis stock = ' + ($stockBefore - 1) + '（领券前 ' + $stockBefore + '，接口回 leftStock=' + $leftAfterResp + '）') (($stockBefore - 1) -eq $leftAfterResp) ($stockBefore - 1).ToString() $leftAfterResp.ToString()
      } else { Skip 'A4-3' 'Redis stock -1' 'Redis 校验不可用' }
      if ($script:infraRedis) {
        try {
          $redisAfter = [int](CouponStock $claimedCouponId)
          Check 'A4-4' ('Redis coupon:stock:' + $claimedCouponId + ' = ' + $redisAfter + '（应为 ' + ($stockBefore - 1) + '）') ($redisAfter -eq ($stockBefore - 1)) ($stockBefore - 1).ToString() $redisAfter.ToString()
        } catch { Check 'A4-4' 'Redis coupon:stock 读取' $false '可读取' $_.Exception.Message }
      } else { Skip 'A4-4' 'Redis coupon:stock' 'Redis 校验不可用' }
      if ($script:infraDb) {
        try {
          $dbAfter = SqlCount ('SELECT COUNT(*) FROM platform_coupon_user WHERE coupon_id=' + $claimedCouponId + ' AND user_id=' + $buyerId + ';')
          Check 'A4-5' ('DB platform_coupon_user 落库（coupon=' + $claimedCouponId + ',user=' + $buyerId + '）行数=' + $dbAfter) ($dbAfter -eq 1) '1' $dbAfter.ToString()
          $dbTotal = SqlCount ('SELECT COUNT(*) FROM platform_coupon_user WHERE coupon_id=' + $claimedCouponId + ';')
          Check 'A4-6' ('DB 总领取数 +1（领前 ' + $dbBefore + ' -> 领后 ' + $dbTotal + '）') ($dbTotal -eq $dbBefore + 1) ($dbBefore + 1).ToString() $dbTotal.ToString()
        } catch { Check 'A4-5' 'DB platform_coupon_user' $false '可查询' $_.Exception.Message }
      } else { Skip 'A4-5' 'DB platform_coupon_user' 'MySQL 校验不可用' }
      # 重复领取
      $dup = Http 'POST' ('/platform/me/coupons/' + $claimedCouponId + '/claim') @{} $buyer.token
      $dupEnv = EnvCode $dup
      $dupInner = IntProp (Prop $dup.obj 'data') @('code')
      Check 'A4-7' ('重复领取被拒：data.code=-2 超限（envelope=' + $dupEnv + ', data.code=' + $dupInner + '）') ($dupEnv -eq 0 -and $dupInner -eq -2) 'envelope=0 且 data.code=-2' (RespBrief $dup)
      if ($script:infraRedis) {
        try {
          $redisDup = [int](CouponStock $claimedCouponId)
          Check 'A4-8' '重复领取不再扣库存（Redis stock 不变）' ($redisDup -eq ($stockBefore - 1)) ($stockBefore - 1).ToString() $redisDup.ToString()
        } catch { }
      }
    }
  }
}

# ================================================================ A5 领券并发（库存不为负）
Head ('A5 设计 3.3 领券并发 x' + $Concurrency + ' 个不同用户（验收 4：库存不为负）')
if ($SkipMutations) {
  Skip 'A5' '领券并发' '传了 -SkipMutations'
} elseif ($Concurrency -le 0) {
  Skip 'A5' '领券并发' '-Concurrency<=0'
} else {
  # 并发券：优先指定 > 排除已用于 A4 的券 > 选库存最大的一张
  $conPick = PickCoupon $ConcurrencyCouponId @([string]$claimedCouponId) 1
  if ($null -eq $conPick) { $conPick = PickCoupon $ConcurrencyCouponId @() 1 }
  if ($null -eq $conPick) {
    Skip 'A5' '领券并发' '没有剩余库存>0 的券（种子库存已耗尽；需重置种子后重跑）'
  } else {
    $ccId = IntProp $conPick @('id')
    $ccStockBefore = -1
    if ($script:infraRedis) { try { $ccStockBefore = [int](CouponStock $ccId) } catch { } }
    $ccDbBefore = -1
    if ($script:infraDb) { try { $ccDbBefore = SqlCount ('SELECT COUNT(*) FROM platform_coupon_user WHERE coupon_id=' + $ccId + ';') } catch { } }
    Info ('并发券 id=' + $ccId + ' Redis 库存=' + $ccStockBefore + ' DB 已领=' + $ccDbBefore + ' 并发数=' + $Concurrency)
    # 并发用户：每轮新建（ddHHmm + 序号），避免"已领过"干扰
    $runTag = Get-Date -Format 'ddHHmm'
    $phones = @(); $tokens = @()
    try {
      for ($i = 1; $i -le $Concurrency; $i++) {
        $ph = '199' + $runTag + $i.ToString('00')
        $phones += $ph
        $s = Login $ph
        $tokens += $s.token
      }
      Check 'A5-1' ('并发用户登录就绪 ' + $tokens.Count + ' 个（' + ($phones -join ',') + '）') ($tokens.Count -eq $Concurrency) $Concurrency.ToString() $tokens.Count.ToString()
    } catch {
      Check 'A5-1' '并发用户登录' $false '全部登录成功' $_.Exception.Message
      $tokens = @()
    }
    if ($tokens.Count -eq $Concurrency) {
      $t0 = Get-Date
      $results = $null
      try { $results = @(ConcurrentPost ('/platform/me/coupons/' + $ccId + '/claim') $tokens @{}) } catch {
        Warn 'A5-2' 'HttpClient 并发失败，退化为顺序请求（并发证据不足）' $_.Exception.Message
        $results = @()
        foreach ($t in $tokens) { $r = Http 'POST' ('/platform/me/coupons/' + $ccId + '/claim') @{} $t; $results += ,([pscustomobject]@{ status=$r.status; raw=$r.raw; obj=$r.obj }) }
      }
      $elapsed = ((Get-Date) - $t0).TotalMilliseconds
      $codes = @(); $inner = @(); $nullResp = 0
      foreach ($r in $results) {
        if ($null -eq $r -or $null -eq $r.obj) { $nullResp++; continue }
        $codes += [int]$r.obj.code
        $inner += [int](Prop $r.obj.data 'code')
      }
      $success = @($inner | Where-Object { $_ -eq 1 }).Count
      $soldOut = @($inner | Where-Object { $_ -eq 0 }).Count
      $limited = @($inner | Where-Object { $_ -eq -2 }).Count
      $notWarm = @($inner | Where-Object { $_ -eq -1 }).Count
      $rateLimited = @($inner | Where-Object { $_ -eq -4 }).Count
      Info ('并发结果：耗时 ' + [int]$elapsed + 'ms；data.code 分布 success=' + $success + ', soldOut=' + $soldOut + ', 超限=' + $limited + ', 未预热=' + $notWarm + ', 限流=' + $rateLimited + '；无响应/非JSON=' + $nullResp)
      Check 'A5-2' ('并发请求全部拿到响应（' + $results.Count + '/' + $Concurrency + '）') ($results.Count -eq $Concurrency -and $nullResp -eq 0) ($Concurrency.ToString() + ' 个响应') ($results.Count.ToString() + ' 个，异常 ' + $nullResp)
      $envOk = @($codes | Where-Object { $_ -ne 0 }).Count -eq 0
      Check 'A5-3' '并发请求信封 code 全部=0（业务结果在 data.code）' $envOk '0 个异常信封' (($codes -join ',') + '；异常 ' + (@($codes | Where-Object { $_ -ne 0 }).Count))
      Check 'A5-4' ('未出现 -1 未预热（预热必须在后端启动时完成）') ($notWarm -eq 0) '0' $notWarm.ToString()
      # 成功数 == min(并发数, 库存)；若未开启 infra，则至少验证 success<=并发数 且 success>=1
      if ($ccStockBefore -ge 0) {
        $expectSuccess = [Math]::Min($Concurrency, $ccStockBefore)
        Check 'A5-5' ('成功数 == min(并发数(' + $Concurrency + '), 库存(' + $ccStockBefore + ')) = ' + $expectSuccess + '（实际 ' + $success + '）') ($success -eq $expectSuccess) $expectSuccess.ToString() $success.ToString()
        if ($script:infraRedis) {
          try {
            $ccStockAfter = [int](CouponStock $ccId)
            Check 'A5-6' ('并发后 Redis 库存 = max(0, ' + $ccStockBefore + ' - ' + $success + ') = ' + ([Math]::Max(0, $ccStockBefore - $success)) + '（实际 ' + $ccStockAfter + '）') ($ccStockAfter -eq [Math]::Max(0, $ccStockBefore - $success)) ([Math]::Max(0, $ccStockBefore - $success)).ToString() $ccStockAfter.ToString()
            Check 'A5-7' ('并发后 Redis 库存不为负（= ' + $ccStockAfter + '）') ($ccStockAfter -ge 0) '>=0' $ccStockAfter.ToString()
          } catch { Check 'A5-6' '并发后 Redis 库存' $false '可读取' $_.Exception.Message }
        }
        if ($script:infraDb) {
          try {
            $ccDbAfter = SqlCount ('SELECT COUNT(*) FROM platform_coupon_user WHERE coupon_id=' + $ccId + ';')
            Check 'A5-8' ('并发后 DB 领取数 = 并发前(' + $ccDbBefore + ') + 成功数(' + $success + ')（实际 ' + $ccDbAfter + '）') ($ccDbAfter -eq $ccDbBefore + $success) ($ccDbBefore + $success).ToString() $ccDbAfter.ToString()
            $dupUsers = SqlCount ('SELECT COUNT(*) FROM (SELECT user_id, COUNT(*) c FROM platform_coupon_user WHERE coupon_id=' + $ccId + ' GROUP BY user_id HAVING c>1) t;')
            Check 'A5-9' '无同一用户重复落库（唯一键兜底生效）' ($dupUsers -eq 0) '0' $dupUsers.ToString()
          } catch { Check 'A5-8' '并发后 DB 领取数' $false '可查询' $_.Exception.Message }
        }
      } else {
        Check 'A5-5' ('并发成功数在 1..' + $Concurrency + ' 之间（实际 ' + $success + '）') ($success -ge 1 -and $success -le $Concurrency) '1..' $success.ToString()
      }
      # 复读 shop/coupons 的 leftStock 与 Redis/DB 一致
      $coupons2 = @(Prop (Http 'GET' ('/platform/shop/coupons?campusId=' + $CampusId)).obj 'data')
      $c2 = @($coupons2 | Where-Object { (IntProp $_ @('id')) -eq $ccId })
      if ($c2.Count -eq 1) {
        $leftInApi = [int](Prop $c2[0] 'leftStock')
        if ($script:infraRedis) {
          try {
            $redisNow = [int](CouponStock $ccId)
            Check 'A5-10' ('接口 leftStock(' + $leftInApi + ') == Redis stock(' + $redisNow + ')') ($leftInApi -eq $redisNow) $redisNow.ToString() $leftInApi.ToString()
          } catch { }
        }
      }
    }
  }
}

# ================================================================ A6 抢购（v1.1：不锁库只扣库存）
Head 'A6 设计 3.3 抢购 + 3.5 platformBuy（v1.1 不锁库只扣库存）'
$grabbedItemId = 0
$script:runTag = Get-Date -Format 'ddHHmm'
if ($SkipMutations) {
  Skip 'A6' '抢购写操作' '传了 -SkipMutations'
} else {
  # ---------------- A6.1 单用户抢购：建单 + 双表落库 + Redis -1 + item 不锁库 ----------------
  $pickFlash = PickFlashItem $GrabItemId @() 1
  if ($null -eq $pickFlash) {
    Skip 'A6' '抢购（单用户）' '没有 leftStock>0 且未抢过的秒杀商品（库存耗尽需重置种子，或用 -GrabItemId 指定）'
  } else {
    $grabItem = IntProp $pickFlash @('itemId')
    $grabSeckillPrice = StrProp $pickFlash @('seckillPrice')
    $grabStockBefore = -1
    if ($script:infraRedis) { try { $grabStockBefore = [int](SeckillStock $grabItem) } catch { } }
    $itemBefore = ''
    if ($script:infraDb) {
      try { $itemBefore = Sql1 ('SELECT CONCAT_WS("|", status, stock, COALESCE(lock_user_id,"<NULL>"), COALESCE(lock_expire_at,"<NULL>")) FROM item WHERE id=' + $grabItem + ';') } catch { }
    }
    try {
      # 抢购用户每轮新建：避免"上一轮已抢过"导致 data.code=-2 干扰本轮断言
      $grabPhone = '199' + $script:runTag + '81'
      $grabBuyer = Login $grabPhone
      $grabBuyerId = IntProp $grabBuyer.user @('id')
      Info ('A6.1 用户 phone=' + $grabPhone + ' userId=' + $grabBuyerId + '；itemId=' + $grabItem + ' seckillPrice=' + $grabSeckillPrice + '；Redis stock=' + $grabStockBefore + '；DB item(status|stock|lock_user|lock_expire)=' + $itemBefore)
      $grab = Http 'POST' ('/platform/me/flash-sale/' + $grabItem + '/grab') @{} $grabBuyer.token
      $grabOk = CheckEnvelope 'A6-1' ('POST /platform/me/flash-sale/' + $grabItem + '/grab code=0') $grab
      $grabData = Prop $grab.obj 'data'
      if ($grabOk) {
        $gSuccess = Prop $grabData 'success'
        $gCode = IntProp $grabData @('code')
        $gOrderId = IntProp $grabData @('orderId')
        $gOrderNo = StrProp $grabData @('orderNo')
        Check 'A6-2' ('抢购 data.success=true / code=1（success=' + $gSuccess + ', code=' + $gCode + '）') ($gSuccess -eq $true -or $gCode -eq 1) 'success=true' (RespBrief $grab)
        Check 'A6-3' ('GrabVO 返回 orderId/orderNo（orderId=' + $gOrderId + ', orderNo=' + $gOrderNo + '）') ($gOrderId -gt 0 -and $gOrderNo.Length -gt 0) 'orderId>0, orderNo 非空' (RespBrief $grab)
        if ($script:infraDb -and $gOrderId -gt 0) {
          try {
            $ord = Sql1 ('SELECT CONCAT_WS("|", status, seller_id, COALESCE(point_id,"<NULL>"), buyer_id, quantity, COALESCE(deal_price,"<NULL>"), COALESCE(remark,"")) FROM meetup_order WHERE id=' + $gOrderId + ';')
            if (-not $ord) { Check 'A6-4' ('meetup_order id=' + $gOrderId + ' 存在') $false '存在' '不存在' }
            else {
              $o = $ord -split '\|'
              Check 'A6-4' ('meetup_order.status=2 待自提（实际 ' + $o[0] + '）') ([string]$o[0] -eq '2') '2' $o[0]
              Check 'A6-5' ('meetup_order.seller_id=1000 平台账号（实际 ' + $o[1] + '）') ([string]$o[1] -eq '1000') '1000' $o[1]
              Check 'A6-6' ('meetup_order.point_id 非空（平台代卖点 id）（实际 ' + $o[2] + '）') ($o[2] -ne '<NULL>') '非空' $o[2]
              Check 'A6-7' ('meetup_order.buyer_id=' + $grabBuyerId + '（实际 ' + $o[3] + '）') ([string]$o[3] -eq [string]$grabBuyerId) $grabBuyerId $o[3]
              Check 'A6-8' ('meetup_order.quantity=1（实际 ' + $o[4] + '）') ([string]$o[4] -eq '1') '1' $o[4]
              if ($grabSeckillPrice) {
                $priceOk = [decimal]$o[5] -eq [decimal]$grabSeckillPrice
                Check 'A6-9' ('meetup_order.deal_price = 秒杀价 ' + $grabSeckillPrice + '（实际 ' + $o[5] + '）') $priceOk $grabSeckillPrice $o[5]
              }
              Check 'A6-10' ('meetup_order.remark 含「平台代卖」（实际 ' + $o[6] + '）') ($o[6].Contains('平台代卖')) '含「平台代卖」' $o[6]
            }
            $rec = SqlCount ('SELECT COUNT(*) FROM platform_seckill_record WHERE item_id=' + $grabItem + ' AND user_id=' + $grabBuyerId + ';')
            Check 'A6-11' ('platform_seckill_record 落库（item=' + $grabItem + ',user=' + $grabBuyerId + '）') ($rec -eq 1) '1' $rec.ToString()
            $recOrder = Sql1 ('SELECT COALESCE(order_id,"<NULL>") FROM platform_seckill_record WHERE item_id=' + $grabItem + ' AND user_id=' + $grabBuyerId + ' LIMIT 1;')
            Check 'A6-12' ('record.order_id 与返回 orderId 一致（' + $recOrder + ' vs ' + $gOrderId + '）') ([string]$recOrder -eq [string]$gOrderId) $gOrderId $recOrder
            # v1.1 关键：不锁库，只扣库存（status=IF(stock-1<=0,4,2)，不写 lock_user_id/lock_expire_at）
            $itemAfter = Sql1 ('SELECT CONCAT_WS("|", status, stock, COALESCE(lock_user_id,"<NULL>"), COALESCE(lock_expire_at,"<NULL>")) FROM item WHERE id=' + $grabItem + ';')
            $ia = $itemAfter -split '\|'
            $stockB = -1
            if ($itemBefore) { $stockB = [int](($itemBefore -split '\|')[1]) }
            if ($stockB -ge 0) {
              Check 'A6-13' ('抢购后 item.stock = 前(' + $stockB + ') - 1 = ' + ($stockB - 1) + '（实际 ' + $ia[1] + '）') ([string]$ia[1] -eq [string]($stockB - 1)) ($stockB - 1) $ia[1]
            } else { Skip 'A6-13' 'item.stock -1' '未取到抢购前 item 行' }
            Check 'A6-14' ('不锁库：item.lock_user_id/lock_expire_at 均为 NULL（实际 ' + $ia[2] + ' / ' + $ia[3] + '）') ($ia[2] -eq '<NULL>' -and $ia[3] -eq '<NULL>') '均为 NULL' ($ia[2] + ' / ' + $ia[3])
            Check 'A6-15' ('不锁库：item.status != 3 且 = (stock>0?2:4)（实际 status=' + $ia[0] + ', stock=' + $ia[1] + '）') ([string]$ia[0] -ne '3' -and [string]$ia[0] -eq $(if ([int]$ia[1] -gt 0) { '2' } else { '4' })) 'status=2 或 4' $ia[0]
          } catch { Check 'A6-4' 'DB 校验抢购结果' $false 'SQL 可执行' $_.Exception.Message }
        } else { Skip 'A6-4..15' 'DB 交叉校验' 'MySQL 校验不可用或 orderId=0' }
        if ($script:infraRedis -and $grabStockBefore -ge 0) {
          try {
            $sAfter = [int](SeckillStock $grabItem)
            Check 'A6-16' ('抢购后 Redis seckill:stock:' + $grabItem + ' = ' + ($grabStockBefore - 1) + '（实际 ' + $sAfter + '）') ($sAfter -eq ($grabStockBefore - 1)) ($grabStockBefore - 1) $sAfter
            $hashVal = RedisHashVal ('ershou:platform:seckill:users:' + $grabItem) ([string]$grabBuyerId)
            Check 'A6-17' ('Redis seckill:users hash[' + $grabBuyerId + '] = 1（实际 ' + $hashVal + '）') ([string]$hashVal -eq '1') '1' $hashVal
          } catch { Check 'A6-16' 'Redis 秒杀库存' $false '可读取' $_.Exception.Message }
        }
        # 同一用户再抢：per_user_limit=1 → 超限/已抢完/场次结束
        $grab2 = Http 'POST' ('/platform/me/flash-sale/' + $grabItem + '/grab') @{} $grabBuyer.token
        $grab2Env = EnvCode $grab2
        $grab2Data = Prop $grab2.obj 'data'
        $grab2Inner = IntProp $grab2Data @('code')
        $grab2Success = Prop $grab2Data 'success'
        $grab2Rejected = ($grab2Env -eq 0 -and $grab2Success -ne $true -and $grab2Inner -in @(-2, 0, -3))
        Check 'A6-18' ('同一用户重复抢购被拒：success!=true 且 code∈{-2超限,0已抢完,-3结束}（envelope=' + $grab2Env + ', success=' + $grab2Success + ', code=' + $grab2Inner + '）') $grab2Rejected '拒绝' (RespBrief $grab2)
        if ($script:infraDb) {
          try {
            $rec2 = SqlCount ('SELECT COUNT(*) FROM platform_seckill_record WHERE item_id=' + $grabItem + ' AND user_id=' + $grabBuyerId + ';')
            Check 'A6-19' '重复抢购不重复落库（record 仍 1 行）' ($rec2 -eq 1) '1' $rec2.ToString()
            $ordCnt = SqlCount ('SELECT COUNT(*) FROM meetup_order WHERE buyer_id=' + $grabBuyerId + ' AND item_id=' + $grabItem + ';')
            Check 'A6-20' '重复抢购不重复建单（meetup_order 仍 1 单）' ($ordCnt -eq 1) '1' $ordCnt.ToString()
          } catch { }
        }
        $grabbedItemId = $grabItem
      }
    } catch { Check 'A6-1' '抢购流程' $false '可执行' $_.Exception.Message }
  }

  # ---------------- A6.2 多用户并发抢购（验收口径 v1.1：库存 N → N 个不同用户全成功、第 N+1 个失败） ----------------
  Head 'A6.2 并发抢购（库存 N -> N 成功、第 N+1 失败；全程不锁库）'
  $conItem = 0
  if ($grabbedItemId -gt 0) { $otherFlash = PickFlashItem 0 @($grabbedItemId) 1 } else { $otherFlash = $null }
  if ($null -ne $otherFlash) { $conItem = IntProp $otherFlash @('itemId') } else { $conItem = $grabbedItemId }
  if ($conItem -le 0) {
    Skip 'A6.2' '并发抢购' '没有可用秒杀商品（A6.1 也无可用的）'
  } else {
    $left = -1
    if ($script:infraRedis) { try { $left = [int](SeckillStock $conItem) } catch { } }
    if ($left -lt 0 -and $null -ne $flash) {
      $fi = @((Prop $flash 'items') | Where-Object { (IntProp $_ @('itemId')) -eq $conItem })
      if ($fi.Count -ge 1) { $left = IntProp $fi[0] @('leftStock') }
    }
    if ($left -le 0) {
      Skip 'A6.2' '并发抢购' ('秒杀商品 ' + $conItem + ' 剩余库存 <=0（前几轮已耗尽，需重置种子后重跑）')
    } else {
      $itemBefore2 = ''
      $recBefore = -1; $ordBefore = -1
      if ($script:infraDb) {
        try {
          $itemBefore2 = Sql1 ('SELECT CONCAT_WS("|", status, stock) FROM item WHERE id=' + $conItem + ';')
          $recBefore = SqlCount ('SELECT COUNT(*) FROM platform_seckill_record WHERE item_id=' + $conItem + ';')
          $ordBefore = SqlCount ('SELECT COUNT(*) FROM meetup_order WHERE item_id=' + $conItem + ' AND seller_id=1000;')
        } catch { }
      }
      # 预检：v1.1 不锁库时「实际可成交份数 = min(Redis 剩余库存, item.stock)」；
      # 种子若把 item.stock 设成 1 而 seckill_stock=5，则 N 个并发不可能全成功（QA 用来定位缺陷）
      $itemStockForCon = -1
      if ($itemBefore2) { $itemStockForCon = [int](($itemBefore2 -split '\|')[1]) }
      if ($itemStockForCon -ge 0) {
        Check 'A6.2-0' ('秒杀商品 item.stock(' + $itemStockForCon + ') >= Redis 剩余库存 N(' + $left + ')（v1.1 不锁库时 DB 库存决定实际可成交份数）') ($itemStockForCon -ge $left) ('>=' + $left) $itemStockForCon
      } else { Skip 'A6.2-0' 'item.stock 预检' 'MySQL 校验不可用' }
      $userCount = $left + 1
      Info ('A6.2 目标 itemId=' + $conItem + ' 当前剩余库存 N=' + $left + ' → 并发用户 ' + $userCount + ' 个（期望 ' + $left + ' 成功 / 1 失败）；DB 抢购前记录=' + $recBefore + '；DB item(status|stock)=' + $itemBefore2)
      $phones = @(); $tokens = @()
      try {
        for ($i = 1; $i -le $userCount; $i++) {
          # 用 20+ 号段，和 A5 的 01..10 不冲突；每轮新建避免"已抢过"
          $ph = '199' + $script:runTag + (20 + $i).ToString('00')
          $phones += $ph
          $tokens += (Login $ph).token
        }
        Check 'A6.2-1' ('并发抢购用户登录就绪 ' + $tokens.Count + ' 个（' + ($phones -join ',') + '）') ($tokens.Count -eq $userCount) $userCount $tokens.Count
      } catch {
        Check 'A6.2-1' '并发抢购用户登录' $false '全部登录成功' $_.Exception.Message
        $tokens = @()
      }
      if ($tokens.Count -eq $userCount) {
        $t0 = Get-Date
        $results = $null
        try { $results = @(ConcurrentPost ('/platform/me/flash-sale/' + $conItem + '/grab') $tokens @{}) }
        catch {
          Warn 'A6.2-2' 'HttpClient 并发失败，退化为顺序请求（并发证据不足）' $_.Exception.Message
          $results = @()
          foreach ($t in $tokens) { $r = Http 'POST' ('/platform/me/flash-sale/' + $conItem + '/grab') @{} $t; $results += ,([pscustomobject]@{ status=$r.status; raw=$r.raw; obj=$r.obj }) }
        }
        $elapsed = ((Get-Date) - $t0).TotalMilliseconds
        $okCount = 0; $badCount = 0; $envCodes = @(); $failInner = @()
        foreach ($r in $results) {
          if ($null -eq $r -or $null -eq $r.obj) { $badCount++; continue }
          $env = [int]$r.obj.code
          $data = Prop $r.obj 'data'
          $isOk = ($env -eq 0) -and ((Prop $data 'success') -eq $true -or (IntProp $data @('code')) -eq 1)
          if ($isOk) { $okCount++ } else { $badCount++; $envCodes += $env; $failInner += (IntProp $data @('code')) }
        }
        Info ('A6.2 并发结果：耗时 ' + [int]$elapsed + 'ms；成功=' + $okCount + ' 失败=' + $badCount + '；失败 data.code=' + ($failInner -join ',') + '；失败 envelope=' + ($envCodes -join ','))
        Check 'A6.2-2' ('并发请求全部拿到响应（' + $results.Count + '/' + $userCount + '）') ($results.Count -eq $userCount) $userCount $results.Count
        Check 'A6.2-3' ('成功数 == 库存 N=' + $left + '（实际 ' + $okCount + '）') ($okCount -eq $left) $left $okCount
        Check 'A6.2-4' ('失败数 == 1（第 N+1 个用户被拒）（实际 ' + $badCount + '）') ($badCount -eq 1) '1' $badCount
        if ($script:infraRedis) {
          try {
            $leftNow = [int](SeckillStock $conItem)
            Check 'A6.2-5' ('并发后 Redis 库存 = 0（实际 ' + $leftNow + '）') ($leftNow -eq 0) '0' $leftNow
            Check 'A6.2-6' ('并发后 Redis 库存不为负（防超卖）') ($leftNow -ge 0) '>=0' $leftNow
          } catch { Check 'A6.2-5' '并发后 Redis 库存' $false '可读取' $_.Exception.Message }
        }
        if ($script:infraDb) {
          try {
            $recAfter = SqlCount ('SELECT COUNT(*) FROM platform_seckill_record WHERE item_id=' + $conItem + ';')
            $ordAfter = SqlCount ('SELECT COUNT(*) FROM meetup_order WHERE item_id=' + $conItem + ' AND seller_id=1000;')
            Check 'A6.2-7' ('platform_seckill_record 增加 ' + $okCount + '（' + $recBefore + ' -> ' + $recAfter + '）') ($recAfter -eq $recBefore + $okCount) ($recBefore + $okCount) $recAfter
            Check 'A6.2-8' ('meetup_order 增加 ' + $okCount + '（' + $ordBefore + ' -> ' + $ordAfter + '）') ($ordAfter -eq $ordBefore + $okCount) ($ordBefore + $okCount) $ordAfter
            $dup = SqlCount ('SELECT COUNT(*) FROM (SELECT user_id, COUNT(*) c FROM platform_seckill_record WHERE item_id=' + $conItem + ' GROUP BY user_id HAVING c>1) t;')
            Check 'A6.2-9' '无同一用户重复落库（唯一键兜底）' ($dup -eq 0) '0' $dup.ToString()
            $itemAfter2 = Sql1 ('SELECT CONCAT_WS("|", status, stock, COALESCE(lock_user_id,"<NULL>"), COALESCE(lock_expire_at,"<NULL>")) FROM item WHERE id=' + $conItem + ';')
            $ia2 = $itemAfter2 -split '\|'
            $stockB2 = -1
            if ($itemBefore2) { $stockB2 = [int](($itemBefore2 -split '\|')[1]) }
            if ($stockB2 -ge 0) {
              Check 'A6.2-10' ('并发后 item.stock = 前(' + $stockB2 + ') - 成功数(' + $okCount + ') = ' + ($stockB2 - $okCount) + '（实际 ' + $ia2[1] + '）') ([string]$ia2[1] -eq [string]($stockB2 - $okCount)) ($stockB2 - $okCount) $ia2[1]
            }
            Check 'A6.2-11' ('并发后 item 仍不锁库（lock 字段 NULL 且 status != 3）（实际 lock_user=' + $ia2[2] + ', lock_expire=' + $ia2[3] + ', status=' + $ia2[0] + '）') ($ia2[2] -eq '<NULL>' -and $ia2[3] -eq '<NULL>' -and [string]$ia2[0] -ne '3') 'NULL/NULL/非3' ($ia2[2] + '/' + $ia2[3] + '/' + $ia2[0])
            Check 'A6.2-12' ('并发扣减后 item.status = (stock>0?2:4)（实际 status=' + $ia2[0] + ', stock=' + $ia2[1] + '）') ([string]$ia2[0] -eq $(if ([int]$ia2[1] -gt 0) { '2' } else { '4' })) '2 或 4' $ia2[0]
          } catch { Check 'A6.2-7' '并发后 DB 校验' $false 'SQL 可执行' $_.Exception.Message }
        }
      }
    }
  }
}

# ================================================================ A7 平台直购（v1.1：不锁库只扣库存 + 完成/取消流程）
Head 'A7 设计 3.3 平台直购 + 3.5 platformBuy（v1.1 不锁库只扣库存）'
if ($SkipMutations) {
  Skip 'A7' '平台直购' '传了 -SkipMutations'
} else {
  $seckillItemIds = @()
  if ($null -ne $flash) { $seckillItemIds = @((Prop $flash 'items') | ForEach-Object { IntProp $_ @('itemId') }) }
  $buyCandidate = $null
  if ($BuyItemId -gt 0) {
    $buyCandidate = @($items | Where-Object { (IntProp $_ @('id')) -eq $BuyItemId })
    if ($buyCandidate.Count -gt 0) { $buyCandidate = $buyCandidate[0] } else { $buyCandidate = $null }
  } else {
    $buyCandidate = @($items | Where-Object {
        (IntProp $_ @('id')) -ne $grabbedItemId -and
        (IntProp $_ @('id')) -gt 0 -and
        (IntProp $_ @('status')) -eq 2 -and
        (IntProp $_ @('stock')) -ge 1 -and
        ($seckillItemIds -notcontains (IntProp $_ @('id')))
      })
    if ($buyCandidate.Count -gt 0) { $buyCandidate = $buyCandidate[0] } else { $buyCandidate = $null }
  }
  if ($null -eq $buyCandidate) {
    Skip 'A7' '平台直购' '没有可直购的平台商品（status=2 且 stock>=1 且非秒杀商品；可能已被本轮/前几轮买掉，需重置种子）'
  } else {
    $buyItem = IntProp $buyCandidate @('id')
    try {
      $buyUser = Login $BuyPhone
      $buyUserId = IntProp $buyUser.user @('id')
      $itemBefore3 = ''; $itemPriceBefore = ''; $creditBefore = -1
      if ($script:infraDb) {
        try {
          $itemBefore3 = Sql1 ('SELECT CONCAT_WS("|", status, stock, COALESCE(price,"<NULL>"), COALESCE(lock_user_id,"<NULL>"), COALESCE(lock_expire_at,"<NULL>")) FROM item WHERE id=' + $buyItem + ';')
          $itemPriceBefore = ($itemBefore3 -split '\|')[2]
        } catch { }
      }
      $meBefore = Http 'GET' '/users/me' $null $buyUser.token
      if ((EnvCode $meBefore) -eq 0) { $creditBefore = IntProp (Prop $meBefore.obj 'data') @('creditScore') }
      Info ('A7 直购用户 phone=' + $BuyPhone + ' userId=' + $buyUserId + '；itemId=' + $buyItem + '；DB item(status|stock|price|lock_user|lock_expire)=' + $itemBefore3 + '；买家信用分=' + $creditBefore)
      $buy = Http 'POST' ('/platform/me/items/' + $buyItem + '/buy') @{ quantity = 1; couponId = $null } $buyUser.token
      $buyOk = CheckEnvelope 'A7-1' ('POST /platform/me/items/' + $buyItem + '/buy code=0') $buy
      $buyData = Prop $buy.obj 'data'
      $bOrderId = IntProp $buyData @('orderId', 'id')
      $bOrderNo = StrProp $buyData @('orderNo')
      Check 'A7-2' ('PlatformBuyVO 返回 orderId/orderNo（orderId=' + $bOrderId + ', orderNo=' + $bOrderNo + '）') ($bOrderId -gt 0) 'orderId>0' (RespBrief $buy)
      if ($script:infraDb -and $bOrderId -gt 0) {
        try {
          $ord = Sql1 ('SELECT CONCAT_WS("|", status, seller_id, COALESCE(point_id,"<NULL>"), buyer_id, quantity, COALESCE(deal_price,"<NULL>"), COALESCE(remark,"")) FROM meetup_order WHERE id=' + $bOrderId + ';')
          if (-not $ord) { Check 'A7-3' ('直购生成 meetup_order id=' + $bOrderId) $false '存在' '不存在' }
          else {
            $o = $ord -split '\|'
            Check 'A7-3' ('直购单 status=2 待自提（实际 ' + $o[0] + '）') ([string]$o[0] -eq '2') '2' $o[0]
            Check 'A7-4' ('直购单 seller_id=1000（实际 ' + $o[1] + '）') ([string]$o[1] -eq '1000') '1000' $o[1]
            Check 'A7-5' ('直购单 point_id 非空（实际 ' + $o[2] + '）') ($o[2] -ne '<NULL>') '非空' $o[2]
            Check 'A7-6' ('直购单 quantity=1（实际 ' + $o[4] + '）') ([string]$o[4] -eq '1') '1' $o[4]
            if ($itemPriceBefore -and $itemPriceBefore -ne '<NULL>') {
              Check 'A7-7' ('直购单 deal_price = 商品现价 ' + $itemPriceBefore + '（实际 ' + $o[5] + '）') ([decimal]$o[5] -eq [decimal]$itemPriceBefore) $itemPriceBefore $o[5]
            }
            Check 'A7-8' ('直购单 remark 含「平台代卖」（实际 ' + $o[6] + '）') ($o[6].Contains('平台代卖')) '含「平台代卖」' $o[6]
          }
          # v1.1 关键：不锁库，只扣库存
          $itemAfter3 = Sql1 ('SELECT CONCAT_WS("|", status, stock, COALESCE(lock_user_id,"<NULL>"), COALESCE(lock_expire_at,"<NULL>")) FROM item WHERE id=' + $buyItem + ';')
          $na = $itemAfter3 -split '\|'
          $stockB3 = -1
          if ($itemBefore3) { $stockB3 = [int](($itemBefore3 -split '\|')[1]) }
          if ($stockB3 -ge 0) {
            Check 'A7-9' ('直购后 item.stock = 前(' + $stockB3 + ') - 1 = ' + ($stockB3 - 1) + '（实际 ' + $na[1] + '）') ([string]$na[1] -eq [string]($stockB3 - 1)) ($stockB3 - 1) $na[1]
          } else { Skip 'A7-9' 'item.stock -1' '未取到直购前 item 行' }
          Check 'A7-10' ('不锁库：item.lock_user_id/lock_expire_at 均为 NULL（实际 ' + $na[2] + ' / ' + $na[3] + '）') ($na[2] -eq '<NULL>' -and $na[3] -eq '<NULL>') '均为 NULL' ($na[2] + ' / ' + $na[3])
          Check 'A7-11' ('不锁库：item.status != 3 且 = (stock>0?2:4)（实际 status=' + $na[0] + ', stock=' + $na[1] + '）') ([string]$na[0] -ne '3' -and [string]$na[0] -eq $(if ([int]$na[1] -gt 0) { '2' } else { '4' })) 'status=2 或 4' $na[0]
        } catch { Check 'A7-3' 'DB 校验直购结果' $false 'SQL 可执行' $_.Exception.Message }
      } else { Skip 'A7-3..11' 'DB 交叉校验' 'MySQL 不可用或 orderId=0' }
      # 买家面交单列表可见
      $mine = Http 'GET' ('/meetup-orders?role=buyer&pageNum=1&pageSize=50') $null $buyUser.token
      if ((EnvCode $mine) -eq 0) {
        $rows = @(Prop (Prop $mine.obj 'data') 'records')
        $hit = @($rows | Where-Object { (IntProp $_ @('id')) -eq $bOrderId -or (IntProp $_ @('itemId')) -eq $buyItem })
        Check 'A7-12' ('买家 /meetup-orders 能看到直购单（匹配 ' + $hit.Count + ' 条）') ($hit.Count -ge 1) '>=1' $hit.Count.ToString()
      } else { Check 'A7-12' '买家 /meetup-orders' $false 'code=0' (RespBrief $mine) }
      # 设计 3.5 第 6 条：买家「我已收到」完成平台单必须可用（v1.1：完成时按剩余库存收口、不扣库存、买家 +2、平台账号不加分）
      if ($bOrderId -gt 0) {
        $confirm = Http 'POST' ('/meetup-orders/' + $bOrderId + '/confirm') @{} $buyUser.token
        $confirmOk = CheckEnvelope 'A7-13' ('POST /meetup-orders/' + $bOrderId + '/confirm（买家我已收到）code=0') $confirm
        if ($confirmOk -and $script:infraDb) {
          try {
            $ord2 = Sql1 ('SELECT CONCAT_WS("|", status, COALESCE(buyer_confirm_at,"<NULL>")) FROM meetup_order WHERE id=' + $bOrderId + ';')
            $oc = $ord2 -split '\|'
            Check 'A7-14' ('完成后面交单 status=3 已完成（实际 ' + $oc[0] + '）') ([string]$oc[0] -eq '3') '3' $oc[0]
            Check 'A7-15' ('完成时写 buyer_confirm_at（实际 ' + $oc[1] + '）') ($oc[1] -ne '<NULL>') '非空' $oc[1]
            $itemAfterConfirm = Sql1 ('SELECT CONCAT_WS("|", status, stock) FROM item WHERE id=' + $buyItem + ';')
            $ic = $itemAfterConfirm -split '\|'
            Check 'A7-16' ('完成后商品按剩余库存收口 status=(stock>0?2:4)、且不扣库存（status=' + $ic[0] + ', stock=' + $ic[1] + '）') ([string]$ic[0] -eq $(if ([int]$ic[1] -gt 0) { '2' } else { '4' })) '2 或 4' $ic[0]
            if ($creditBefore -ge 0) {
              $meAfter = Http 'GET' '/users/me' $null $buyUser.token
              $creditAfter = if ((EnvCode $meAfter) -eq 0) { IntProp (Prop $meAfter.obj 'data') @('creditScore') } else { -1 }
              Check 'A7-17' ('平台单完成后买家信用分 +2（' + $creditBefore + ' -> ' + $creditAfter + '）') ($creditAfter -eq $creditBefore + 2) ($creditBefore + 2) $creditAfter
            }
            $platformCredit = Sql1 'SELECT credit_score FROM sys_user WHERE id=1000;'
            $platformLog = SqlCount 'SELECT COUNT(*) FROM credit_log WHERE user_id=1000;'
            Check 'A7-18' ('平台账号不加信用分（credit_score=' + ([string]$platformCredit).Trim() + '，应为 100）') (([string]$platformCredit).Trim() -eq '100') '100' $platformCredit
            Check 'A7-19' ('平台账号不写信用流水（credit_log 行数=' + $platformLog + '，应为 0）') ($platformLog -eq 0) '0' $platformLog
          } catch { Check 'A7-14' '完成流程 DB 校验' $false 'SQL 可执行' $_.Exception.Message }
        } elseif ($confirmOk) { Skip 'A7-14..19' '完成流程 DB 校验' 'MySQL 不可用' }
      }
      # 可选（-TestCancelRestore）：取消平台单要把下单时扣掉的库存补回（restorePlatformStock）
      if ($TestCancelRestore) {
        Head 'A7.x 取消平台单回补库存（-TestCancelRestore）'
        $cancelCandidate = @($items | Where-Object {
            (IntProp $_ @('id')) -ne $buyItem -and
            (IntProp $_ @('id')) -ne $grabbedItemId -and
            (IntProp $_ @('id')) -gt 0 -and
            (IntProp $_ @('status')) -eq 2 -and
            (IntProp $_ @('stock')) -ge 1 -and
            ($seckillItemIds -notcontains (IntProp $_ @('id')))
          })
        if ($cancelCandidate.Count -eq 0) {
          Skip 'A7.x' '取消回补库存' '没有第二件可直购的平台商品（仅剩 890/891，本轮已被占用）'
        } else {
          $cancelItem = IntProp $cancelCandidate[0] @('id')
          try {
            $stockBc = -1
            if ($script:infraDb) { try { $stockBc = SqlCount ('SELECT stock FROM item WHERE id=' + $cancelItem + ';') } catch { } }
            $buy2 = Http 'POST' ('/platform/me/items/' + $cancelItem + '/buy') @{ quantity = 1; couponId = $null } $buyUser.token
            $order2 = IntProp (Prop $buy2.obj 'data') @('orderId', 'id')
            if ((EnvCode $buy2) -ne 0 -or $order2 -le 0) {
              Check 'A7.x-1' ('第二单直购（item=' + $cancelItem + '，用于取消回补）') $false '建单成功' (RespBrief $buy2)
            } else {
              $cancel = Http 'POST' ('/meetup-orders/' + $order2 + '/cancel') @{ reason = 'QA 验收：取消回补库存' } $buyUser.token
              Check 'A7.x-1' ('POST /meetup-orders/' + $order2 + '/cancel code=0') ((EnvCode $cancel) -eq 0) 'code=0' (RespBrief $cancel)
              if ($script:infraDb) {
                try {
                  $ordC = Sql1 ('SELECT status FROM meetup_order WHERE id=' + $order2 + ';')
                  Check 'A7.x-2' ('取消后面交单 status=4 已取消（实际 ' + $ordC + '）') ([string]$ordC -eq '4') '4' $ordC
                  $itemC = Sql1 ('SELECT CONCAT_WS("|", status, stock) FROM item WHERE id=' + $cancelItem + ';')
                  $icx = $itemC -split '\|'
                  if ($stockBc -ge 0) {
                    Check 'A7.x-3' ('取消后 item.stock 回补到 ' + $stockBc + '（实际 ' + $icx[1] + '）') ([string]$icx[1] -eq [string]$stockBc) $stockBc $icx[1]
                  }
                  Check 'A7.x-4' ('取消后商品回到在售 status=2（实际 ' + $icx[0] + '）') ([string]$icx[0] -eq '2') '2' $icx[0]
                } catch { Check 'A7.x-2' '取消回补 DB 校验' $false 'SQL 可执行' $_.Exception.Message }
              } else { Skip 'A7.x-2..4' '取消回补 DB 校验' 'MySQL 不可用' }
            }
          } catch { Check 'A7.x-1' '取消回补库存流程' $false '可执行' $_.Exception.Message }
        }
      }
    } catch { Check 'A7-1' '平台直购流程' $false '可执行' $_.Exception.Message }
  }
}

# ================================================================ A8 管理员负向（普通用户被拒）
Head 'A8 设计 3.4 负向：普通用户访问 admin 接口必须被拒'
$adminNeg = Http 'GET' ('/platform/admin/points?campusId=' + $CampusId) $null $buyer.token
$negCode = EnvCode $adminNeg
Check 'A8-1' ('普通用户 GET /platform/admin/points 被拒：code=10003 无权限（实际 ' + $negCode + '）') ($negCode -eq 10003) '10003' (RespBrief $adminNeg)
$adminNeg2 = Http 'POST' '/platform/admin/points' @{ campusId = $CampusId; name = 'QA越权测试点'; address = 'QA'; lng = 110.4; lat = 25.3 } $buyer.token
$negCode2 = EnvCode $adminNeg2
Check 'A8-2' ('普通用户 POST /platform/admin/points 被拒：code=10003（实际 ' + $negCode2 + '）') ($negCode2 -eq 10003) '10003' (RespBrief $adminNeg2)

# ================================================================ A9 管理员正向 CRUD
Head 'A9 设计 3.4 正向：管理员点位 CRUD'
# -AdminOnly：跳过 A4~A7 的写操作、只重跑管理员组（Phase 2 授权后补跑用，避免重复消耗券/秒杀库存）
if ($SkipAdmin -or ($SkipMutations -and -not $AdminOnly)) {
  Skip 'A9' '管理员点位 CRUD' $(if ($SkipAdmin) { '-SkipAdmin' } else { '-SkipMutations（可加 -AdminOnly 只跑管理员组）' })
} else {
  try {
    $admin = Login $AdminPhone
    $adminId = IntProp $admin.user @('id')
    Info ('管理员测试账号 phone=' + $AdminPhone + ' userId=' + $adminId + '；若被拒需 Lead 执行：UPDATE sys_user SET role_flags = role_flags | 8 WHERE id = ' + $adminId + ';')
    $adminList = Http 'GET' ('/platform/admin/points?campusId=' + $CampusId) $null $admin.token
    $admCode = EnvCode $adminList
    if ($admCode -eq 10003) {
      Check 'A9-0' ('管理员权限未授予（userId=' + $adminId + '）') $false 'code=0' ('需要 Lead 执行：UPDATE sys_user SET role_flags = role_flags | 8 WHERE id = ' + $adminId + '; 然后重跑本脚本')
    } elseif ($admCode -ne 0) {
      Check 'A9-0' ('管理员 GET /platform/admin/points（code=' + $admCode + '）') $false 'code=0' (RespBrief $adminList)
    } else {
      Check 'A9-0' ('管理员 GET /platform/admin/points code=0（userId=' + $adminId + '）') $true 'code=0' 'ok'
      $admPoints = @(Prop $adminList.obj 'data')
      Check 'A9-1' ('管理员点位列表（含停用）>= 3（实际 ' + $admPoints.Count + '）') ($admPoints.Count -ge 3) '>=3' $admPoints.Count.ToString()
      # 新增
      $newName = $AdminNewPointName + '-' + (Get-Date -Format 'HHmmss')
      $createBody = @{ campusId = $CampusId; name = $newName; address = 'QA 自动化地址 1 号'; lng = 110.4189; lat = 25.3134; openHours = '09:00-18:00'; contactPhone = '0773-0000000'; guideText = 'QA 自动化创建，可删除'; sort = 999; status = 1 }
      $create = Http 'POST' '/platform/admin/points' $createBody $admin.token
      $createOk = CheckEnvelope 'A9-2' 'POST /platform/admin/points 新建 code=0' $create
      $newId = 0
      if ($createOk) {
        $cd = Prop $create.obj 'data'
        if ($cd -is [int] -or $cd -is [long] -or $cd -is [double] -or $cd -is [decimal]) { $newId = [int]$cd }
        else { $newId = IntProp $cd @('id', 'pointId') }
        Check 'A9-3' ('新建点位返回 id（' + $newId + '）') ($newId -gt 0) 'id>0' (RespBrief $create)
        $script:createdPointId = $newId
      }
      if ($newId -gt 0) {
        # 出现在公开 shop/points
        $pub1 = @(Prop (Http 'GET' ('/platform/shop/points?campusId=' + $CampusId)).obj 'data')
        $hit1 = @($pub1 | Where-Object { (IntProp $_ @('id')) -eq $newId -or (StrProp $_ @('name')) -eq $newName })
        Check 'A9-4' '新增点位出现在公开 shop/points' ($hit1.Count -eq 1) '1 条' $hit1.Count.ToString()
        # 编辑
        $edit = Http 'PUT' ('/platform/admin/points/' + $newId) @{ name = ($newName + '-已编辑'); address = 'QA 自动化地址 1 号 B'; lng = 110.4190; lat = 25.3135 } $admin.token
        CheckEnvelope 'A9-5' 'PUT /platform/admin/points/{id} 编辑 code=0' $edit | Out-Null
        $pub2 = @(Prop (Http 'GET' ('/platform/shop/points?campusId=' + $CampusId)).obj 'data')
        $hit2 = @($pub2 | Where-Object { (IntProp $_ @('id')) -eq $newId })
        $name2 = if ($hit2.Count -eq 1) { StrProp $hit2[0] @('name') } else { '' }
        Check 'A9-6' ('编辑生效（name=' + $name2 + '）') ($name2 -eq ($newName + '-已编辑')) ($newName + '-已编辑') $name2
        # 停用
        $off = Http 'POST' ('/platform/admin/points/' + $newId + '/status?status=0') @{} $admin.token
        CheckEnvelope 'A9-7' 'POST /platform/admin/points/{id}/status?status=0 停用 code=0' $off | Out-Null
        $pub3 = @(Prop (Http 'GET' ('/platform/shop/points?campusId=' + $CampusId)).obj 'data')
        $hit3 = @($pub3 | Where-Object { (IntProp $_ @('id')) -eq $newId })
        Check 'A9-8' '停用后不出现在公开 shop/points' ($hit3.Count -eq 0) '0 条' $hit3.Count.ToString()
        $adm3 = @(Prop (Http 'GET' ('/platform/admin/points?campusId=' + $CampusId) $null $admin.token).obj 'data')
        $hit3b = @($adm3 | Where-Object { (IntProp $_ @('id')) -eq $newId })
        $st3 = if ($hit3b.Count -eq 1) { IntProp $hit3b[0] @('status') } else { -1 }
        Check 'A9-9' ('停用后管理员列表里 status=0（实际 ' + $st3 + '）') ($st3 -eq 0) '0' $st3.ToString()
        # 重新启用
        $on = Http 'POST' ('/platform/admin/points/' + $newId + '/status?status=1') @{} $admin.token
        CheckEnvelope 'A9-10' 'POST .../status?status=1 重新启用 code=0' $on | Out-Null
        # 软删除
        $del = Http 'DELETE' ('/platform/admin/points/' + $newId) $null $admin.token
        CheckEnvelope 'A9-11' 'DELETE /platform/admin/points/{id} 软删除 code=0' $del | Out-Null
        if ($script:infraDb) {
          try {
            $row = Sql1 ('SELECT CONCAT_WS("|", COUNT(*), COALESCE(MAX(status),-1)) FROM platform_consign_point WHERE id=' + $newId + ';')
            $r = $row -split '\|'
            Check 'A9-12' ('软删除：DB 行仍存在（历史商品引用不破坏），status=0（行数=' + $r[0] + ',status=' + $r[1] + '）') (([string]$r[0] -eq '1') -and ([string]$r[1] -eq '0')) '1 行且 status=0' $row
          } catch { Check 'A9-12' '软删除 DB 校验' $false 'SQL 可执行' $_.Exception.Message }
        }
        $pub4 = @(Prop (Http 'GET' ('/platform/shop/points?campusId=' + $CampusId)).obj 'data')
        $hit4 = @($pub4 | Where-Object { (IntProp $_ @('id')) -eq $newId })
        Check 'A9-13' '软删除后不出现在公开 shop/points' ($hit4.Count -eq 0) '0 条' $hit4.Count.ToString()
        $script:createdPointId = 0   # 已软删，无需清理
        # 新增缺参校验（campusId 必填）
        $bad = Http 'POST' '/platform/admin/points' @{ name = 'QA缺参'; address = 'QA' } $admin.token
        Check 'A9-14' ('新建点位缺 campusId 被参数校验拦截（code=' + (EnvCode $bad) + '）') ((EnvCode $bad) -ne 0) 'code!=0（10001 参数校验）' (RespBrief $bad)
      }
    }
  } catch { Check 'A9-0' '管理员流程' $false '可执行' $_.Exception.Message }
}

# ================================================================ A10 缓存重建
Head 'A10 设计 3.4 缓存重建 / 第四节预热'
if ($SkipAdmin -or ($SkipMutations -and -not $AdminOnly)) {
  Skip 'A10' '缓存重建' $(if ($SkipAdmin) { '-SkipAdmin' } else { '-SkipMutations（可加 -AdminOnly 只跑管理员组）' })
} else {
  try {
    $adminToken = $script:tokenStore[$AdminPhone]
    if (-not $adminToken) { $adminToken = (Login $AdminPhone).token }
    $refresh = Http 'POST' '/platform/admin/cache/refresh' @{} $adminToken
    $refreshOk = CheckEnvelope 'A10-1' 'POST /platform/admin/cache/refresh code=0' $refresh
    if ($script:infraRedis) {
      try {
        # ⚠️ 必须给每个元素整体加括号：PowerShell 里逗号优先级高于 +，
        #   写成 @('a:' + $x, 'b:' + $x) 会被解析成 'a:' + ($x, 'b:') + ... → 拼成单个带空格的字符串，
        #   结果 EXISTS 收到一个假 key，A10-2 误报（2026-10-17 踩过，用 Redis MONITOR 定位）。
        $need = @(
          ('ershou:platform:points:' + $CampusId),
          ('ershou:platform:coupons:' + $CampusId),
          ('ershou:platform:overview:' + $CampusId),
          ('ershou:platform:flash:' + $CampusId)
        )
        Info ('A10 调试：refresh 响应=' + (RespBrief $refresh) + '；refresh 前缓存 key 存在数=' + (RedisExistCount $need))
        # 一次 EXISTS 问 4 个 key；控制器 refresh 里是同步回填，理论上立即存在。
        # 关键：控制器 read-through 回填若在"删除之后才落 Redis"，会有极短窗口；这里 200ms×12 轮询取证。
        $existCount = RedisExistCount $need
        $poll = @()
        for ($i = 0; $i -lt 12; $i++) {
          if ($existCount -eq $need.Count) { break }
          Start-Sleep -Milliseconds 200
          $existCount = RedisExistCount $need
          $poll += $existCount
        }
        $missingKeys = @()
        foreach ($k in $need) { if (-not (RedisExists $k)) { $missingKeys += $k } }
        Info ('A10 调试：refresh 后缓存 key 存在数=' + $existCount + '/4；轮询序列=' + ($poll -join '->') + '；缺失=' + $(if ($missingKeys.Count -eq 0) { '无' } else { $missingKeys -join ',' }))
        Check 'A10-2' ('重建后 4 类业务缓存 key 齐全（存在 ' + $existCount + '/4，缺失: ' + ($(if ($missingKeys.Count -eq 0) { '无' } else { $missingKeys -join ',' })) + '）') ($existCount -eq $need.Count -and $missingKeys.Count -eq 0) '4 个 key 都存在' ('存在 ' + $existCount + '/4；缺失 ' + ($missingKeys -join ','))
        $stockKeys = @(RedisLines @('--scan','--pattern','ershou:platform:coupon:stock:*'))
        $seckillKeys = @(RedisLines @('--scan','--pattern','ershou:platform:seckill:stock:*'))
        Check 'A10-3' ('重建后库存 key 存在（券 ' + $stockKeys.Count + ' 个 / 应收 3；秒杀 ' + $seckillKeys.Count + ' 个 / 应收 2）') ($stockKeys.Count -eq 3 -and $seckillKeys.Count -eq 2) '券 3 / 秒杀 2' ('券 ' + $stockKeys.Count + ' / 秒杀 ' + $seckillKeys.Count)
      } catch { Check 'A10-2' 'Redis 缓存 key 校验' $false '可读取' $_.Exception.Message }
    } else { Skip 'A10-2' '缓存 key 校验' 'Redis 校验不可用' }
  } catch { Check 'A10-1' '缓存重建' $false '可执行' $_.Exception.Message }
}

# ================================================================ A11 回归
Head 'A11 回归（验收标准 7：登录/首页/详情/购物车/面交单）'
$regressions = @(
  @{ id='A11-1'; name='GET /categories 分类'; method='GET'; path='/categories'; token='' },
  @{ id='A11-2'; name='GET /items?pageNum=1 首页列表'; method='GET'; path='/items?pageNum=1&pageSize=10'; token='' },
  @{ id='A11-3'; name='GET /users/me 当前用户（带 token）'; method='GET'; path='/users/me'; token=$buyer.token },
  @{ id='A11-4'; name='GET /cart 购物车（带 token）'; method='GET'; path='/cart'; token=$buyer.token },
  @{ id='A11-5'; name='GET /meetup-orders 面交单（带 token）'; method='GET'; path='/meetup-orders?role=buyer&pageNum=1&pageSize=10'; token=$buyer.token },
  @{ id='A11-6'; name='GET /favorites 收藏（带 token）'; method='GET'; path='/favorites'; token=$buyer.token },
  @{ id='A11-7'; name='GET /trade-points 面交点'; method='GET'; path=('/trade-points?campusId=' + $CampusId); token='' },
  @{ id='A11-8'; name='GET /chat/unread 未读（带 token）'; method='GET'; path='/chat/unread'; token=$buyer.token }
)
foreach ($rg in $regressions) {
  $r = Http $rg.method $rg.path $null $rg.token
  CheckEnvelope $rg.id $rg.name $r | Out-Null
}
# 详情：取首页第一条商品
try {
  $detailRecs = @(Prop (Prop $homeResp.obj 'data') 'records')
  if ($detailRecs.Count -gt 0) {
    $did = IntProp $detailRecs[0] @('id')
    $detailR = Http 'GET' ('/items/' + $did)
    CheckEnvelope 'A11-9' ('GET /items/' + $did + ' 商品详情') $detailR | Out-Null
  } else { Skip 'A11-9' '商品详情' '首页列表为空' }
} catch { Skip 'A11-9' '商品详情' ('异常: ' + $_.Exception.Message) }

# ================================================================ 清理 + 汇总
Head 'A12 清理与汇总'
if ($script:createdPointId -gt 0) {
  try {
    $adminToken = $script:tokenStore[$AdminPhone]
    if ($adminToken) { $null = Http 'DELETE' ('/platform/admin/points/' + $script:createdPointId) $null $adminToken; Info ('已清理测试点位 id=' + $script:createdPointId) }
  } catch { Warn 'A12-1' '清理测试点位失败' $_.Exception.Message }
}
$summary = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  base = $base
  campusId = $CampusId
  concurrency = $Concurrency
  pass = $script:pass; fail = $script:fail; skip = $script:skip; warn = $script:warn
  testUsers = [ordered]@{ buyer=$BuyerPhone; admin=$AdminPhone; grab=$GrabPhone; buy=$BuyPhone }
  evidence = @($script:checkEvidence)
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
Write-Host '提示：本脚本创建了测试用户/面交单；券与秒杀库存被消耗属预期（已领取/已抢购即不消耗）。' -ForegroundColor DarkGray
Write-Host '------------------------------------------------------------' -ForegroundColor White
exit ([Math]::Min($script:fail, 250))
