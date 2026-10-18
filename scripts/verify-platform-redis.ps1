<#
.SYNOPSIS
  第 2 轮「平台代卖」验收 · Redis 侧证据脚本（只读，除 DEL 外不写；默认零写入）。

.DESCRIPTION
  对应 `智能体团队/第2轮-平台代卖-设计.md`：
    - 第四节 Redis 设计（11 类 key）与 Lua 预热口径
    - 第七节验收标准 5：`--scan --pattern 'ershou:platform:*'` 列出全部 key，并贴出 stock/users hash 的值

  断言分 7 组：
    R1 环境：docker 可用、ershou-redis 容器在跑、PING=PONG
    R2 key 清单：按家族分组（缓存 / 库存 / 用户hash / 限流 / 预热锁），并打印每个 key 的 TYPE/TTL/值
    R3 库存 key：值为整数且 >= 0（**防超卖核心断言**）
    R4 用户 hash：每字段值 >= 1
    R5 TTL：缓存与库存 key TTL > 0；预热锁（若存在）TTL <= 30 秒
    R6 与 DB 对账（-WithDb）：sum(users hash) == total_stock - stock == COUNT(领取/抢购行)
    R7 前后对比：-Mode snapshot 存快照；-Mode diff 与快照对比（领券/抢购后 stock -1、hash +1 的证据）

.PARAMETER Mode
  all（默认）= 清单 + 断言；snapshot = 存快照；diff = 与快照对比；list = 只列 key。

.EXAMPLE
  # 单独看 key
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-redis.ps1
  # 领券/抢购前后对比（验收标准 4 的 stock -1 证据）
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-redis.ps1 -Mode snapshot -Snapshot .build\qa\redis-before.json
  powershell -ExecutionPolicy Bypass -File .\scripts\verify-platform-redis.ps1 -Mode diff     -Snapshot .build\qa\redis-before.json

.NOTES
  Redis 密码从仓库根 `.env` 的 REDIS_PASSWORD 读取；容器名默认 ershou-redis，库默认 0。
  退出码 = 失败断言数（0 = 全通过）。
#>
param(
  [string]$Container = 'ershou-redis',
  [string]$Password  = '',
  [int]   $Db        = -1,
  [ValidateSet('all','list','snapshot','diff')][string]$Mode = 'all',
  [string]$Snapshot  = '',
  [switch]$WithDb,
  [string]$OutJson   = ''
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

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
if (-not $Password) { $Password = $env:REDIS_PASSWORD }
if ($Db -lt 0) { $Db = if ($env:REDIS_DATABASE) { [int]$env:REDIS_DATABASE } else { 0 } }

$qaDir = Join-Path $root '.build\qa'
if (-not (Test-Path $qaDir)) { New-Item -ItemType Directory -Force -Path $qaDir | Out-Null }
$outFull = if ($OutJson) { if ([System.IO.Path]::IsPathRooted($OutJson)) { $OutJson } else { Join-Path $root $OutJson } } else { Join-Path $qaDir 'platform-redis-result.json' }
$snapFull = if ($Snapshot) { if ([System.IO.Path]::IsPathRooted($Snapshot)) { $Snapshot } else { Join-Path $root $Snapshot } } else { Join-Path $qaDir 'redis-snapshot.json' }

# ---------------------------------------------------------------- 结果收集
$script:pass = 0; $script:fail = 0; $script:skip = 0; $script:warn = 0
$script:results = New-Object System.Collections.ArrayList
function Check([string]$Id, [string]$Item, [bool]$Ok, [string]$Expect, [string]$Actual) {
  if ($Ok) { $script:pass++ } else { $script:fail++ }
  $status = if ($Ok) { 'PASS' } else { 'FAIL' }
  Write-Host ('  [' + $status + '] ' + $Id + ' ' + $Item) -ForegroundColor $(if ($Ok) { 'Green' } else { 'Red' })
  if (-not $Ok) { Write-Host ('         期望: ' + $Expect) -ForegroundColor Red; Write-Host ('         实际: ' + $Actual) -ForegroundColor Red }
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status=$status; expect=$Expect; actual=$Actual })
}
function Skip([string]$Id, [string]$Item, [string]$Reason) {
  $script:skip++
  Write-Host ('  [SKIP] ' + $Id + ' ' + $Item + ' —— ' + $Reason) -ForegroundColor DarkYellow
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status='SKIP'; expect=''; actual=$Reason })
}
function Warn([string]$Id, [string]$Item, [string]$Detail) {
  $script:warn++
  Write-Host ('  [WARN] ' + $Id + ' ' + $Item + ' —— ' + $Detail) -ForegroundColor Yellow
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status='WARN'; expect=''; actual=$Detail })
}
function Head([string]$Text) { Write-Host ''; Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan }

# ---------------------------------------------------------------- redis-cli（docker exec）
function Redis([string[]]$argv) {
  $a = @('exec', $Container, 'redis-cli', '-a', $Password, '--no-auth-warning', '-n', ([string]$Db)) + $argv
  $out = & docker @a 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('docker exec redis-cli 失败（exit ' + $LASTEXITCODE + '）：' + ($argv -join ' ')) }
  return @($out)
}
function Redis1([string[]]$argv) {
  $rows = @(Redis $argv)
  if ($rows.Count -eq 0) { return '' }
  return ([string]$rows[0]).Trim()
}
function RedisList([string[]]$argv) { return @(Redis $argv | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ } ) }
function RedisType([string]$key)  { return Redis1 @('TYPE', $key) }
function RedisTTL([string]$key)   { return Redis1 @('TTL', $key) }
function RedisStr([string]$key)   { return Redis1 @('GET', $key) }
function RedisHash([string]$key) {
  $lines = @(RedisList @('HGETALL', $key))
  $map = [ordered]@{}
  for ($i = 0; $i -lt $lines.Count - 1; $i += 2) { $map[$lines[$i]] = $lines[$i + 1] }
  return $map
}
function RedisValues([string]$key) { return RedisList @('HVALS', $key) }

# ---------------------------------------------------------------- 环境前置
Write-Host '============================================================' -ForegroundColor White
Write-Host ' 第 2 轮 · 平台代卖验收 · Redis 检查' -ForegroundColor White
Write-Host (' 容器: ' + $Container + '  库: ' + $Db + '  模式: ' + $Mode) -ForegroundColor DarkGray
Write-Host (' 时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')) -ForegroundColor DarkGray
Write-Host '============================================================' -ForegroundColor White
Head 'R1 环境'
$dockerOk = $true
try { $null = & docker version --format '{{.Server.Version}}' 2>$null; if ($LASTEXITCODE -ne 0) { $dockerOk = $false } } catch { $dockerOk = $false }
Check 'R1-1' 'docker 可用' $dockerOk 'docker version 成功' 'docker 不可用'
if (-not $dockerOk) {
  Write-Host 'docker 不可用，后续全部跳过。' -ForegroundColor Red
  exit 1
}
$psOut = (& docker ps --filter ('name=^/' + $Container + '$') --format '{{.Names}}|{{.Status}}' 2>$null) -join ''
Check 'R1-2' ('容器 ' + $Container + ' 在运行') ($psOut -match [regex]::Escape($Container)) ('Up ...') ($psOut)
try {
  $pong = Redis1 @('PING')
  Check 'R1-3' 'Redis PING' ($pong -eq 'PONG') 'PONG' $pong
} catch {
  Check 'R1-3' 'Redis PING' $false 'PONG' ('异常: ' + $_.Exception.Message)
  exit 1
}

# ---------------------------------------------------------------- 列举 key
Head 'R2 key 清单（设计第四节）'
$keys = @(RedisList @('--scan', '--pattern', 'ershou:platform:*') | Sort-Object)
Check 'R2-1' ('ershou:platform:* key 数量 > 0（实际 ' + $keys.Count + '）') ($keys.Count -gt 0) '>0' ($keys.Count.ToString() + '（0 说明预热没跑或 Redis 库不对）')
$keyInfo = [ordered]@{}
foreach ($k in $keys) {
  $type = RedisType $k
  $ttl = RedisTTL $k
  $detail = ''
  $valMap = $null
  switch ($type) {
    'string' {
      $v = RedisStr $k
      if ($v.Length -gt 160) { $detail = $v.Substring(0, 160) + '...(' + $v.Length + ' chars)' } else { $detail = $v }
    }
    'hash' {
      $valMap = RedisHash $k
      $detail = (($valMap.Keys | ForEach-Object { $_.ToString() + '=>' + $valMap[$_] }) -join ', ')
      if ($detail.Length -gt 200) { $detail = $detail.Substring(0, 200) + '...' }
    }
    default { $detail = '(未展开)' }
  }
  $keyInfo[$k] = [ordered]@{ type=$type; ttl=$ttl; value=$detail }
  Write-Host ('  ' + $k) -ForegroundColor DarkGray
  Write-Host ('      type=' + $type + ' ttl=' + $ttl + ' value=' + $detail) -ForegroundColor DarkGray
}

# 家族分组
function KeysOf([string]$Pattern) { return @($keys | Where-Object { $_ -like $Pattern }) }
$cacheKeys   = @($keys | Where-Object { $_ -match '^ershou:platform:(points|coupons|overview|flash):' })
$couponStock = @(KeysOf 'ershou:platform:coupon:stock:*')
$sekillStock = @(KeysOf 'ershou:platform:seckill:stock:*')
$couponUsers = @(KeysOf 'ershou:platform:coupon:users:*')
$seckillUsers = @(KeysOf 'ershou:platform:seckill:users:*')
$rateKeys    = @(KeysOf 'ershou:platform:rate:*')
$lockKeys    = @(KeysOf 'ershou:platform:lock:*')
Warn 'R2-2' '缓存 key（points|coupons|overview|flash）' ($cacheKeys.Count.ToString() + ' 个: ' + ($cacheKeys -join ', '))
Warn 'R2-3' '券库存 key' (($couponStock -join ', '))
Warn 'R2-4' '秒杀库存 key' (($sekillStock -join ', '))
Warn 'R2-5' '券用户 hash' (($couponUsers -join ', '))
Warn 'R2-6' '秒杀用户 hash' (($seckillUsers -join ', '))
Warn 'R2-7' '限流 key（存在属正常，5 秒后自动过期）' (($rateKeys -join ', '))
Warn 'R2-8' '预热锁 key' (($lockKeys -join ', '))
Check 'R2-9' '存在至少 1 个库存 key（券或秒杀，说明预热已跑）' (($couponStock.Count + $sekillStock.Count) -gt 0) '>=1' (($couponStock.Count + $sekillStock.Count).ToString())
if ($cacheKeys.Count -eq 0) { Skip 'R2-10' '业务缓存 key' '尚无请求触发缓存写入（overview/coupons/points/flash 首次请求才写）' } else { Check 'R2-10' ('业务缓存 key 存在 ' + $cacheKeys.Count + ' 个') $true '>=1' $cacheKeys.Count.ToString() }

# ---------------------------------------------------------------- R3 库存非负（防超卖）
Head 'R3 库存 key：整数且 >= 0（防超卖 core）'
foreach ($k in @($couponStock + $sekillStock)) {
  $v = RedisStr $k
  $isInt = $v -match '^-?\d+$'
  $numOk = $isInt -and ([long]$v -ge 0)
  Check ('R3') ('库存 ' + $k + ' = ' + $v) $numOk '整数且 >=0' $v
}

# ---------------------------------------------------------------- R4 users hash 合法
Head 'R4 用户 hash：每字段 >= 1'
foreach ($k in @($couponUsers + $seckillUsers)) {
  $vals = @(RedisValues $k)
  $bad = @($vals | Where-Object { -not ($_ -match '^\d+$') -or [int]$_ -lt 1 })
  Check ('R4') ($k + ' 字段数=' + $vals.Count + ' 值=' + (($vals | Select-Object -First 10) -join ',')) ($vals.Count -gt 0 -and $bad.Count -eq 0) '全部 >=1 的整数' (($bad -join ',') + ' 异常')
}

# ---------------------------------------------------------------- R5 TTL
Head 'R5 TTL（设计第四节）'
$ttlExpect = @{ 'points' = 600; 'coupons' = 60; 'overview' = 60; 'flash' = 30 }
foreach ($k in $cacheKeys) {
  $m = [regex]::Match($k, '^ershou:platform:(points|coupons|overview|flash):')
  $fam = $m.Groups[1].Value
  $ttl = [int](RedisTTL $k)
  $max = $ttlExpect[$fam]
  Check ('R5-1') ('缓存 TTL ' + $k + ' = ' + $ttl + 's（应 0<ttl<=' + $max + '）') ($ttl -gt 0 -and $ttl -le $max) ('0 < ttl <= ' + $max) $ttl.ToString()
}
foreach ($k in @($couponStock + $sekillStock)) {
  $ttl = [int](RedisTTL $k)
  Check ('R5-2') ('库存 key TTL ' + $k + ' = ' + $ttl + '（应 >0，设计为 end_at+1h）') ($ttl -gt 0) '>0' $ttl.ToString()
}
foreach ($k in $lockKeys) {
  $ttl = [int](RedisTTL $k)
  Check ('R5-3') ('预热锁 ' + $k + ' TTL=' + $ttl + '（应 <=30，长期存在=没释放）') ($ttl -le 30) '<=30' $ttl.ToString()
}

# ---------------------------------------------------------------- R6 与 DB 对账
function Get-DbStock([int]$couponId) {
  # 返回 total_stock|COUNT(platform_coupon_user)；SQL 走 stdin（-e 传参时 PS 会吞双引号）
  $q = 'SELECT CONCAT_WS(''|'', total_stock, (SELECT COUNT(*) FROM platform_coupon_user u WHERE u.coupon_id=' + $couponId + ')) FROM platform_coupon WHERE id=' + $couponId + ';'
  $out = $q | & $script:mysql @script:mysqlArgs 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 失败：' + $q) }
  return (($out | Select-Object -First 1) -as [string]).Trim()
}
function Get-DbSeckillStock([int]$itemId) {
  $q = 'SELECT CONCAT_WS(''|'', seckill_stock, (SELECT COUNT(*) FROM platform_seckill_record r WHERE r.item_id=' + $itemId + ')) FROM platform_flash_sale_item WHERE item_id=' + $itemId + ';'
  $out = $q | & $script:mysql @script:mysqlArgs 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 失败：' + $q) }
  return (($out | Select-Object -First 1) -as [string]).Trim()
}
$script:mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$script:mysqlArgs = @(
  ('--host=' + $(if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' })),
  ('--port=' + $(if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' })),
  ('--user=' + $(if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'ershou' })),
  ('--password=' + $env:MYSQL_PASSWORD),
  ('--database=' + $(if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'ershou' })),
  '-N', '-B'
)
Head 'R6 与 DB 对账（-WithDb）'
if (-not $WithDb) {
  Skip 'R6' '与 DB 对账' '未传 -WithDb（需要 MySQL 客户端与 .env 凭据）'
} elseif (-not (Test-Path $script:mysql)) {
  Skip 'R6' '与 DB 对账' ('找不到 mysql 客户端: ' + $script:mysql)
} else {
  # 券：sum(users hash) 应 == total_stock - stock == COUNT(coupon_user)
  foreach ($k in $couponUsers) {
    $cid = [int]([regex]::Match($k, 'coupon:users:(\d+)$').Groups[1].Value)
    $sum = 0; foreach ($v in @(RedisValues $k)) { $sum += [int]$v }
    $stockKey = 'ershou:platform:coupon:stock:' + $cid
    $stock = [int](RedisStr $stockKey)
    try {
      $dbv = (Get-DbStock $cid) -split '\|'
      $dbTotal = [int]$dbv[0]; $dbRows = [int]$dbv[1]
      $expectLeft = $dbTotal - $dbRows
      Check ('R6-1') ('券 ' + $cid + ' sum(users)=' + $sum + ' == DB 已领 ' + $dbRows) ($sum -eq $dbRows) $dbRows.ToString() $sum.ToString()
      Check ('R6-2') ('券 ' + $cid + ' Redis stock=' + $stock + ' == total_stock(' + $dbTotal + ')-已领(' + $dbRows + ')=' + $expectLeft) ($stock -eq $expectLeft) $expectLeft.ToString() $stock.ToString()
      Check ('R6-3') ('券 ' + $cid + ' stock>=0') ($stock -ge 0) '>=0' $stock.ToString()
    } catch {
      Check ('R6-1') ('券 ' + $cid + ' 与 DB 对账') $false 'SQL 可执行' ('异常: ' + $_.Exception.Message)
    }
  }
  # 秒杀：sum(users hash) == COUNT(record) == seckill_stock - stock
  foreach ($k in $seckillUsers) {
    $itemId = [int]([regex]::Match($k, 'seckill:users:(\d+)$').Groups[1].Value)
    $sum = 0; foreach ($v in @(RedisValues $k)) { $sum += [int]$v }
    $stockKey = 'ershou:platform:seckill:stock:' + $itemId
    $stock = [int](RedisStr $stockKey)
    try {
      $dbv = (Get-DbSeckillStock $itemId) -split '\|'
      $dbStock = [int]$dbv[0]; $dbRows = [int]$dbv[1]
      $expectLeft = $dbStock - $dbRows
      Check ('R6-4') ('秒杀 ' + $itemId + ' sum(users)=' + $sum + ' == DB 已抢 ' + $dbRows) ($sum -eq $dbRows) $dbRows.ToString() $sum.ToString()
      Check ('R6-5') ('秒杀 ' + $itemId + ' Redis stock=' + $stock + ' == seckill_stock(' + $dbStock + ')-已抢(' + $dbRows + ')=' + $expectLeft) ($stock -eq $expectLeft) $expectLeft.ToString() $stock.ToString()
      Check ('R6-6') ('秒杀 ' + $itemId + ' stock>=0') ($stock -ge 0) '>=0' $stock.ToString()
    } catch {
      Check ('R6-4') ('秒杀 ' + $itemId + ' 与 DB 对账') $false 'SQL 可执行' ('异常: ' + $_.Exception.Message)
    }
  }
}

# ---------------------------------------------------------------- R7 快照 / 前后对比
Head 'R7 快照 / 前后对比'
$snapObj = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  container = $Container; db = $Db
  keys = $keyInfo
}
if ($Mode -eq 'snapshot') {
  $snapObj | ConvertTo-Json -Depth 8 | Set-Content -Path $snapFull -Encoding UTF8
  Write-Host ('  快照已写入: ' + $snapFull) -ForegroundColor Green
  Check 'R7-1' '快照写入成功' (Test-Path $snapFull) '存在' $snapFull
}
if ($Mode -eq 'diff') {
  if (-not (Test-Path $snapFull)) {
    Check 'R7-2' ('快照存在: ' + $snapFull) $false '存在' '不存在（先 -Mode snapshot 生成）'
  } else {
    $before = (Get-Content -Raw -Encoding UTF8 $snapFull | ConvertFrom-Json).keys
    $allKeys = @($before.PSObject.Properties.Name + @($keyInfo.Keys)) | Sort-Object -Unique
    $added = 0; $removed = 0; $changed = 0
    foreach ($k in $allKeys) {
      $hasBefore = $null -ne $before.PSObject.Properties[$k]
      $hasAfter  = $keyInfo.Contains($k)
      if (-not $hasBefore -and $hasAfter)  { $added++;   Write-Host ('  [ADDED]   ' + $k + ' = ' + $keyInfo[$k].value) -ForegroundColor Green; continue }
      if ($hasBefore -and -not $hasAfter)  { $removed++; Write-Host ('  [REMOVED] ' + $k) -ForegroundColor Yellow; continue }
      $b = $before.$k; $a = $keyInfo[$k]
      if ([string]$b.value -ne [string]$a.value -or [string]$b.ttl -ne [string]$a.ttl) {
        $changed++
        Write-Host ('  [CHANGED] ' + $k) -ForegroundColor Cyan
        Write-Host ('      before: ttl=' + $b.ttl + ' value=' + $b.value) -ForegroundColor DarkGray
        Write-Host ('      after : ttl=' + $a.ttl + ' value=' + $a.value) -ForegroundColor DarkGray
        if ($k -match 'ershou:platform:(coupon|seckill):stock:') {
          $delta = [long]$a.value - [long]$b.value
          Check ('R7-3') ('库存变化 ' + $k + ' delta=' + $delta) ($delta -le 0) 'delta <= 0（只应减少）' $delta.ToString()
        }
        if ($k -match 'ershou:platform:(coupon|seckill):users:') {
          Write-Host ('      用户 hash 变化（before 字段数=' + (@($b.value -split ',').Count - 1) + '，after 字段数=' + $a.value.Split(',').Count + '）') -ForegroundColor DarkGray
        }
      }
    }
    Check 'R7-4' ('对比完成：ADDED=' + $added + ' REMOVED=' + $removed + ' CHANGED=' + $changed) $true '打印差异' ('ADDED=' + $added + ',REMOVED=' + $removed + ',CHANGED=' + $changed)
  }
}

# ---------------------------------------------------------------- 汇总
$summary = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  container = $Container; db = $Db; mode = $Mode
  pass = $script:pass; fail = $script:fail; skip = $script:skip; warn = $script:warn
  keys = $keys
  results = @($script:results)
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $outFull -Encoding UTF8

Write-Host ''
Write-Host '------------------------------------------------------------' -ForegroundColor White
if ($script:fail -eq 0) {
  Write-Host ('Redis 检查全部通过：' + $script:pass + ' 项断言（WARN ' + $script:warn + '，SKIP ' + $script:skip + '，key ' + $keys.Count + ' 个）') -ForegroundColor Green
} else {
  Write-Host ('Redis 检查：通过 ' + $script:pass + ' 项，失败 ' + $script:fail + ' 项，SKIP ' + $script:skip + '，WARN ' + $script:warn) -ForegroundColor Red
}
Write-Host ('结果 JSON: ' + $outFull) -ForegroundColor DarkGray
if ($keys.Count -gt 0) {
  Write-Host ''
  Write-Host '全部 key（验收标准 5 证据，可直接粘贴）：' -ForegroundColor DarkGray
  $keys | ForEach-Object { Write-Host ('  ' + $_) -ForegroundColor White }
}
Write-Host '------------------------------------------------------------' -ForegroundColor White
exit ([Math]::Min($script:fail, 250))
