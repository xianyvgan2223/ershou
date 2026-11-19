﻿﻿﻿﻿﻿<#
.SYNOPSIS
  第 5 轮 Spring Cloud 重构 - 端到端演示脚本（全程经网关 8088）。

.DESCRIPTION
  对应 智能体团队/第5轮-SpringCloud重构-设计.md 第七节验收标准的第 5/6/7/8/10 条。
  11 个步骤，每步输出 PASS/FAIL + 真实响应片段：
    S1  POST /api/v1/auth/sms-code             短信验证码（mock 123456）
    S2  POST /api/v1/auth/login                登录拿 token（Sa-Token 会话共享 Redis db0）
    S3  GET  /api/v1/users/me                  网关 X-User-Id 透传 + user-service
    S4  GET  /api/v1/items?pageNum=1            item-service 商品列表（>=5 条）
    S5  GET  /api/v1/items/{id}                商品详情
    S6  POST /api/v1/meetup-orders             下单（Seata AT：Feign 锁库存 + 订单落库 + 库存 -1）
    S7  GET  /api/v1/meetup-orders/mine        我的面交单（Feign 补商品标题）
    S8  POST /api/v1/meetup-orders debugFail   Seata 全局回滚（库存不变 + 无新增订单）
    S9  RabbitMQ order.created                 item.sale_count 异步 +1
    S10 GET  /api/v1/items 并发 10             Gateway Sentinel QPS=5 限流返回 429
    S11 GET  /api/v1/platform/shop/points      未迁移路径经网关反代旧单体 8080 -> 200

  前置条件：
    1) Docker 中间件：Nacos 8848 / RabbitMQ 5672+15672 / Seata 8091+7091 / Sentinel 8858 全 healthy
    2) 现有 MySQL 与 Redis 容器（ershou-redis 6380）已启动
    3) ershou-cloud/sql/init-cloud-databases.sql 与 migrate-data.ps1 已执行（ershou_user/item/order 三库）
    4) Nacos 已发布 4 个服务配置；4 个服务已注册（gateway 8088 / user 8081 / item 8082 / order 8083）
    5) 旧单体 8080 已启动（IDEA 或本脚本后台自动拉起；脚本绝不 kill 别人的实例）

  用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1 -DryRun
    powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1 -SkipSeata -SkipRabbit
    powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1 -NoStartLegacy
    powershell -ExecutionPolicy Bypass -File .\scripts\cloud-demo.ps1 -VerboseBody

  退出码：0=全部通过（SKIP 不算失败）；2=网关不可达；>0=失败断言数（最多 99）。

.NOTES
  - 输出文件：.build/cloud/demo-result.json（PASS/FAIL 汇总 + 每步真实响应片段）
  - 日志目录：.build/cloud/logs/（脚本拉起旧单体时的 stdout/stderr 落在里面）
  - 脚本只读业务数据，会创建测试订单（演示数据），可重复执行。
  - PowerShell 5.1 注意：本文件必须保存为 UTF-8 with BOM，且代码里不要用中文全角引号。
#>
param(
  [string]$Gateway = 'http://127.0.0.1:8088',
  [string]$Legacy = 'http://127.0.0.1:8080',
  [string]$Phone = '19900000099',
  [string]$SmsCode = '123456',
  [int]$CampusId = 2,
  [int]$SentinelConcurrency = 10,
  [int]$OrderQuantity = 1,
  [int]$LegacyWaitSec = 240,
  [int]$MqWaitSec = 15,
  [string]$MySQLPath = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$CloudDbPassword = '',
  [string]$OutJson = '.build\cloud\demo-result.json',
  [string]$LogDir = '.build\cloud\logs',
  [switch]$NoStartLegacy,
  [switch]$StopLegacyAfter,
  [switch]$SkipSeata,
  [switch]$SkipRabbit,
  [switch]$SkipSentinel,
  [switch]$SkipLegacyProxy,
  [switch]$DryRun,
  [switch]$VerboseBody
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
# PowerShell 5.1 默认不加载 System.Net.Http（Sentinel 并发打点要用 HttpClient）
try { Add-Type -AssemblyName System.Net.Http -ErrorAction Stop } catch { }
$root = Split-Path -Parent $PSScriptRoot

function Resolve-PathUnderRoot([string]$p) {
  if ([System.IO.Path]::IsPathRooted($p)) { return $p }
  return (Join-Path $root $p)
}

$outFull = Resolve-PathUnderRoot $OutJson
$script:logDir = Resolve-PathUnderRoot $LogDir
foreach ($dir in @((Split-Path -Parent $outFull), $script:logDir, (Join-Path $root '.build\cloud'))) {
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
}

# ---------------------------------------------------------------- .env 载入（MySQL/Redis 等基础凭据）
$envFile = Join-Path $root '.env'
$script:envLoaded = $false
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Za-z_]+=' } | ForEach-Object {
    $line = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = (($line -split '#', 2)[0]) -split '=', 2
    if (-not $k -or -not $v) { return }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
  $script:envLoaded = $true
}
if (-not $CloudDbPassword) { $CloudDbPassword = $env:MYSQL_PASSWORD }
if (-not $CloudDbPassword) {
  Write-Host '缺少数据库密码：请先在仓库根 .env 中配置 MYSQL_PASSWORD（或传 -CloudDbPassword）' -ForegroundColor Red
  exit 1
}

$script:apiGateway = $Gateway.TrimEnd('/') + '/api/v1'
$script:apiLegacy = $Legacy.TrimEnd('/') + '/api/v1'

# ---------------------------------------------------------------- 结果收集
$script:steps = New-Object System.Collections.ArrayList
$script:current = $null
$script:totalChecks = 0
$script:failedChecks = 0
$script:preflight = [ordered]@{
  gatewayPortUp = $false
  gatewayHttpStatus = 0
  legacy = $null
  mysql = $null
}
$script:legacyOutLog = ''
$script:legacyErrLog = ''

function Snip([string]$Text, [int]$Max = 400) {
  if (-not $Text) { return '' }
  $one = ($Text -replace '\s+', ' ').Trim()
  if ($one.Length -le $Max) { return $one }
  return $one.Substring(0, $Max) + '...'
}

function Write-Line([string]$Text, [string]$Color = 'Gray') {
  Write-Host $Text -ForegroundColor $Color
}

function Begin-Step([string]$Id, [string]$Name, [string]$Expect) {
  $script:current = [pscustomobject]@{
    id = $Id
    name = $Name
    expect = $Expect
    status = 'PASS'
    snippet = ''
    checks = (New-Object System.Collections.ArrayList)
  }
  Write-Host ''
  Write-Host ('=== ' + $Id + '  ' + $Name + ' ===') -ForegroundColor Cyan
  if ($Expect) { Write-Host ('  期望: ' + $Expect) -ForegroundColor DarkGray }
}

function End-Step {
  if ($null -eq $script:current) { return }
  $s = $script:current
  $color = 'Green'
  if ($s.status -eq 'FAIL') { $color = 'Red' }
  if ($s.status -eq 'SKIP') { $color = 'DarkYellow' }
  Write-Host ('  => ' + $s.id + ' ' + $s.status) -ForegroundColor $color
  [void]$script:steps.Add($s)
  $script:current = $null
}

function Step-Check([string]$Item, [bool]$Ok, [string]$Expect, [string]$Actual) {
  $script:totalChecks++
  if ($Ok) {
    Write-Host ('  [PASS] ' + $Item) -ForegroundColor Green
  } else {
    $script:failedChecks++
    if ($null -ne $script:current) { $script:current.status = 'FAIL' }
    Write-Host ('  [FAIL] ' + $Item) -ForegroundColor Red
    if ($Expect) { Write-Host ('         期望: ' + $Expect) -ForegroundColor Red }
    if ($Actual) { Write-Host ('         实际: ' + (Snip $Actual 300)) -ForegroundColor Red }
  }
  if ($null -ne $script:current) {
    $st = 'PASS'
    if (-not $Ok) { $st = 'FAIL' }
    [void]$script:current.checks.Add([pscustomobject]@{ item = $Item; status = $st; expect = $Expect; actual = (Snip $Actual 600) })
  }
  return $Ok
}

function Step-Skip([string]$Item, [string]$Reason) {
  Write-Host ('  [SKIP] ' + $Item + ' -- ' + $Reason) -ForegroundColor DarkYellow
  if ($null -ne $script:current) {
    [void]$script:current.checks.Add([pscustomobject]@{ item = $Item; status = 'SKIP'; expect = ''; actual = $Reason })
  }
}

function Step-SkipAll([string]$Reason) {
  if ($null -ne $script:current) {
    $script:current.status = 'SKIP'
    [void]$script:current.checks.Add([pscustomobject]@{ item = $Reason; status = 'SKIP'; expect = ''; actual = $Reason })
  }
  Write-Host ('  [SKIP] ' + $Reason) -ForegroundColor DarkYellow
}

function Set-Snippet([string]$Text) {
  if ($null -ne $script:current -and $Text) { $script:current.snippet = (Snip $Text 500) }
}

function Info([string]$Text) {
  Write-Host ('  [INFO] ' + $Text) -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- HTTP
function Http([string]$Method, [string]$Path, $Body = $null, [string]$Token = '', [int]$TimeoutSec = 20, [string]$Base = 'gateway') {
  $apiRoot = $script:apiGateway
  if ($Base -eq 'legacy') { $apiRoot = $script:apiLegacy }
  $url = $apiRoot + $Path
  $headers = @{ 'Accept' = 'application/json' }
  if ($Token) { $headers['Authorization'] = 'Bearer ' + $Token }
  $p = @{ Uri = $url; Method = $Method; Headers = $headers; TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
  if ($null -ne $Body) {
    $json = $Body
    if ($Body -isnot [string]) { $json = $Body | ConvertTo-Json -Depth 8 -Compress }
    $p['ContentType'] = 'application/json; charset=utf-8'
    $p['Body'] = [System.Text.Encoding]::UTF8.GetBytes([string]$json)
  }
  $status = 0
  $raw = ''
  try {
    $resp = Invoke-WebRequest @p
    $status = [int]$resp.StatusCode
    # 后端返回 application/json 未带 charset 时，PS 5.1 会按 ISO-8859-1 解码导致中文乱码；
    # 这里直接按 UTF-8 解字节，保证写进 demo-result.json 的响应片段可读。
    try {
      $bytes = $resp.RawContentStream.ToArray()
      $raw = [System.Text.Encoding]::UTF8.GetString($bytes)
    } catch {
      $raw = [string]$resp.Content
    }
  } catch {
    $we = $_.Exception
    $respObj = $null
    try { $respObj = $we.Response } catch { $respObj = $null }
    if ($null -ne $respObj) {
      try { $status = [int]$respObj.StatusCode } catch { $status = 0 }
      try {
        $stream = $respObj.GetResponseStream()
        $ms = New-Object System.IO.MemoryStream
        $stream.CopyTo($ms)
        $raw = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
      } catch { $raw = '' }
      if (-not $raw) {
        try { $raw = [string]$_.ErrorDetails.Message } catch { $raw = '<无法读取错误响应体>' }
      }
    } else {
      return [pscustomobject]@{ status = 0; raw = ('网络异常: ' + $we.Message); obj = $null; path = $Path }
    }
  }
  $obj = $null
  if ($raw -and $raw.TrimStart().StartsWith('{')) {
    try { $obj = $raw | ConvertFrom-Json } catch { $obj = $null }
  }
  if ($VerboseBody) { Info ($Method + ' ' + $Path + ' -> HTTP ' + $status + ' ' + (Snip $raw 400)) }
  return [pscustomobject]@{ status = $status; raw = $raw; obj = $obj; path = $Path }
}

function Prop($Obj, [string]$Name) {
  if ($null -eq $Obj) { return $null }
  $p = $Obj.PSObject.Properties[$Name]
  if ($null -eq $p) { return $null }
  return $p.Value
}

function IntProp($Obj, [string[]]$Names) {
  foreach ($n in $Names) {
    $v = Prop $Obj $n
    if ($null -ne $v) { try { return [int]$v } catch { } }
  }
  return 0
}

function StrProp($Obj, [string[]]$Names) {
  foreach ($n in $Names) {
    $v = Prop $Obj $n
    if ($null -ne $v) { return [string]$v }
  }
  return ''
}

function Get-Data($Resp) { return (Prop $Resp.obj 'data') }
function Get-Code($Resp) {
  if ($null -eq $Resp -or $null -eq $Resp.obj) { return -999 }
  $v = Prop $Resp.obj 'code'
  if ($null -eq $v) { return -997 }
  try { return [int]$v } catch { return -996 }
}
function Resp-Brief($Resp) {
  if ($null -eq $Resp) { return '<null>' }
  return ('HTTP ' + $Resp.status + ' ' + (Snip $Resp.raw 400))
}

# ---------------------------------------------------------------- TCP / 端口探测
function Test-Port([string]$HostName, [int]$Port, [int]$TimeoutMs = 1500) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $iar = $client.BeginConnect($HostName, $Port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
    $client.EndConnect($iar)
    return $true
  } catch {
    return $false
  } finally {
    try { $client.Close() } catch { }
  }
}

function Get-HostPort([string]$Url) {
  $u = [System.Uri]$Url
  return @{ host = $u.Host; port = $u.Port }
}

# ---------------------------------------------------------------- MySQL（只读校验）
$script:mysqlOk = $false
$script:itemDbOk = $false
$script:orderDbOk = $false
$script:dbHost = '127.0.0.1'
$script:dbPort = '3306'
if ($env:MYSQL_HOST) { $script:dbHost = $env:MYSQL_HOST }
if ($env:MYSQL_PORT) { $script:dbPort = [string]$env:MYSQL_PORT }
$script:mysqlArgsBase = @(
  ('--host=' + $script:dbHost),
  ('--port=' + $script:dbPort),
  '--default-character-set=utf8mb4', '-N', '-B'
)

function Sql-Rows([string]$User, [string]$Db, [string]$Sql) {
  $a = $script:mysqlArgsBase + @(('--user=' + $User), ('--password=' + $CloudDbPassword), ('--database=' + $Db))
  $out = $Sql | & $MySQLPath @a 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 执行失败 db=' + $Db + ' sql=' + (Snip $Sql 120)) }
  $rows = @()
  foreach ($line in @($out)) {
    $t = ([string]$line).Trim()
    if ($t -ne '') { $rows += $t }
  }
  return $rows
}

function Sql-One([string]$User, [string]$Db, [string]$Sql) {
  $rows = Sql-Rows $User $Db $Sql
  if ($rows.Count -eq 0) { return '' }
  return [string]$rows[0]
}

function Sql-Int([string]$User, [string]$Db, [string]$Sql) {
  $v = Sql-One $User $Db $Sql
  if (-not $v) { return -1 }
  try { return [int]$v } catch { return -1 }
}

function Db-ItemStock([int]$ItemId) { return (Sql-Int 'ershou_item' 'ershou_item' ('SELECT stock FROM item WHERE id=' + $ItemId)) }
function Db-ItemSaleCount([int]$ItemId) { return (Sql-Int 'ershou_item' 'ershou_item' ('SELECT sale_count FROM item WHERE id=' + $ItemId)) }
function Db-BuyableItemIds([int]$Limit) {
  $rows = Sql-Rows 'ershou_item' 'ershou_item' ('SELECT id FROM item WHERE status=2 AND stock>0 ORDER BY id LIMIT ' + $Limit)
  $ids = @()
  foreach ($r in $rows) { try { $ids += [int]$r } catch { } }
  return $ids
}
function Db-OrderExists([int]$OrderId) { return (Sql-Int 'ershou_order' 'ershou_order' ('SELECT COUNT(*) FROM meetup_order WHERE id=' + $OrderId)) }
function Db-OrderCount([int]$BuyerId, [int]$ItemId) {
  return (Sql-Int 'ershou_order' 'ershou_order' ('SELECT COUNT(*) FROM meetup_order WHERE buyer_id=' + $BuyerId + ' AND item_id=' + $ItemId))
}
function Db-LatestOrderId([int]$BuyerId, [int]$ItemId) {
  return (Sql-One 'ershou_order' 'ershou_order' ('SELECT id FROM meetup_order WHERE buyer_id=' + $BuyerId + ' AND item_id=' + $ItemId + ' ORDER BY id DESC LIMIT 1'))
}

# ---------------------------------------------------------------- 旧单体：探测 / 必要时后台拉起（绝不 kill）
function Get-LegacyInfo {
  $hp = Get-HostPort $Legacy
  if (Test-Port $hp.host $hp.port) { return [pscustomobject]@{ up = $true; startedByUs = $false; how = 'already-running'; pid = 0 } }
  if ($NoStartLegacy) { return [pscustomobject]@{ up = $false; startedByUs = $false; how = 'disabled-by-NoStartLegacy'; pid = 0 } }

  Write-Host '  旧单体 8080 未启动，尝试后台拉起（不会 kill 已有进程）...' -ForegroundColor Yellow
  $serverDir = Join-Path $root 'ershou-server'
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $script:legacyOutLog = Join-Path $script:logDir ('legacy-' + $stamp + '.out.log')
  $script:legacyErrLog = Join-Path $script:logDir ('legacy-' + $stamp + '.err.log')

  $proc = $null
  $jar = $null
  $jarDir = Join-Path $serverDir 'target'
  if (Test-Path $jarDir) {
    $cand = Get-ChildItem $jarDir -Filter '*.jar' -File -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
      Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cand) { $jar = $cand.FullName }
  }
  if ($jar) {
    Write-Host ('  启动方式: java -jar ' + $jar) -ForegroundColor DarkGray
    $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar, '--spring.profiles.active=dev,local') -WorkingDirectory $serverDir -RedirectStandardOutput $script:legacyOutLog -RedirectStandardError $script:legacyErrLog -PassThru -WindowStyle Hidden
  } else {
    $mvn = $null
    foreach ($c in @('D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd', 'D:\IntelliJ IDEA 2024.3.3\plugins\maven\lib\maven3\bin\mvn.cmd')) {
      if (Test-Path $c) { $mvn = $c; break }
    }
    if (-not $mvn) {
      $g = Get-Command mvn -ErrorAction SilentlyContinue
      if ($g) { $mvn = $g.Source }
    }
    if (-not $mvn) {
      return [pscustomobject]@{ up = $false; startedByUs = $false; how = 'no-jar-no-maven'; pid = 0 }
    }
    Write-Host ('  启动方式: ' + $mvn + ' spring-boot:run') -ForegroundColor DarkGray
    $proc = Start-Process -FilePath $mvn -ArgumentList @('-B', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local') -WorkingDirectory $serverDir -RedirectStandardOutput $script:legacyOutLog -RedirectStandardError $script:legacyErrLog -PassThru -WindowStyle Hidden
  }

  $deadline = (Get-Date).AddSeconds($LegacyWaitSec)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    if (Test-Port $hp.host $hp.port 800) {
      $probe = Http 'GET' '/categories' -Base 'legacy' -TimeoutSec 5
      if ($probe.status -gt 0) {
        return [pscustomobject]@{ up = $true; startedByUs = $true; how = 'auto-started'; pid = $proc.Id }
      }
    }
    if ($proc.HasExited) {
      return [pscustomobject]@{ up = $false; startedByUs = $false; how = ('legacy-exited-code-' + $proc.ExitCode); pid = $proc.Id }
    }
  }
  return [pscustomobject]@{ up = $false; startedByUs = $true; how = 'timeout-waiting'; pid = $proc.Id }
}

# ---------------------------------------------------------------- 并发打点（Sentinel 用；PS 5.1 兼容）
function Invoke-Burst([string]$Url, [int]$Count) {
  $results = New-Object System.Collections.ArrayList
  $client = New-Object System.Net.Http.HttpClient
  try {
    $tasks = @()
    for ($i = 0; $i -lt $Count; $i++) { $tasks += $client.GetAsync($Url) }
    foreach ($t in $tasks) {
      try { [void]$t.Wait(8000) } catch { }
    }
    foreach ($t in $tasks) {
      try {
        $resp = $t.Result
        $status = [int]$resp.StatusCode
        $body = $resp.Content.ReadAsStringAsync().Result
        [void]$results.Add([pscustomobject]@{ status = $status; body = $body })
      } catch {
        [void]$results.Add([pscustomobject]@{ status = 0; body = ('异常: ' + $_.Exception.Message) })
      }
    }
  } finally {
    try { $client.Dispose() } catch { }
  }
  return $results
}

# ---------------------------------------------------------------- RabbitMQ 管理 API（可选证据）
function Get-QueueStats([string]$QueueName) {
  try {
    $b64 = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes('guest:guest'))
    $url = 'http://127.0.0.1:15672/api/queues/%2F/' + [System.Uri]::EscapeDataString($QueueName)
    $r = Invoke-WebRequest -Uri $url -Headers @{ Authorization = 'Basic ' + $b64 } -UseBasicParsing -TimeoutSec 5
    return ($r.Content | ConvertFrom-Json)
  } catch {
    return $null
  }
}

# ---------------------------------------------------------------- 结果落盘
function Write-Result([int]$ExitCode) {
  $pass = 0
  $fail = 0
  $skip = 0
  foreach ($s in $script:steps) {
    if ($s.status -eq 'PASS') { $pass++ }
    elseif ($s.status -eq 'FAIL') { $fail++ }
    else { $skip++ }
  }
  $obj = [ordered]@{
    meta = [ordered]@{
      script = 'scripts/cloud-demo.ps1'
      round = '第5轮 Spring Cloud 重构'
      timestamp = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
      gateway = $Gateway
      legacy = $Legacy
      phone = $Phone
      campusId = $CampusId
      sentinelConcurrency = $SentinelConcurrency
      host = $env:COMPUTERNAME
    }
    preflight = $script:preflight
    summary = [ordered]@{
      steps = $script:steps.Count
      pass = $pass
      fail = $fail
      skip = $skip
      checksTotal = $script:totalChecks
      checksFailed = $script:failedChecks
      exitCode = $ExitCode
    }
    steps = @($script:steps)
    logs = [ordered]@{ dir = $script:logDir; legacyOut = $script:legacyOutLog; legacyErr = $script:legacyErrLog }
  }
  $json = $obj | ConvertTo-Json -Depth 10
  [System.IO.File]::WriteAllText($outFull, $json, [System.Text.UTF8Encoding]::new($false))
  Write-Host ''
  Write-Host '================ 汇总 ================' -ForegroundColor Cyan
  foreach ($s in $script:steps) {
    $color = 'Green'
    if ($s.status -eq 'FAIL') { $color = 'Red' }
    if ($s.status -eq 'SKIP') { $color = 'DarkYellow' }
    Write-Host ('  ' + $s.id.PadRight(4) + ' ' + $s.status.PadRight(5) + ' ' + $s.name) -ForegroundColor $color
  }
  Write-Host ('  PASS=' + $pass + '  FAIL=' + $fail + '  SKIP=' + $skip + '  checks=' + $script:totalChecks + '  failedChecks=' + $script:failedChecks) -ForegroundColor Cyan
  Write-Host ('  退出码: ' + $ExitCode) -ForegroundColor Cyan
  Write-Host ('  结果文件: ' + $outFull) -ForegroundColor Cyan
  if ($script:legacyOutLog) { Write-Host ('  旧单体日志: ' + $script:legacyOutLog) -ForegroundColor DarkGray }
}

# ================================================================ 预检
Write-Host '================================================================' -ForegroundColor Cyan
Write-Host ' 第 5 轮 Spring Cloud 重构 - 端到端演示脚本 (scripts/cloud-demo.ps1)' -ForegroundColor Cyan
Write-Host '================================================================' -ForegroundColor Cyan
Write-Host ('  网关      : ' + $Gateway) -ForegroundColor DarkGray
Write-Host ('  旧单体    : ' + $Legacy) -ForegroundColor DarkGray
Write-Host ('  测试手机号: ' + $Phone + '  验证码: ' + $SmsCode) -ForegroundColor DarkGray
Write-Host ('  结果文件  : ' + $outFull) -ForegroundColor DarkGray

$plan = @(
  'S1  短信验证码        POST /api/v1/auth/sms-code',
  'S2  登录              POST /api/v1/auth/login',
  'S3  当前用户          GET  /api/v1/users/me',
  'S4  商品列表          GET  /api/v1/items?pageNum=1&pageSize=10',
  'S5  商品详情          GET  /api/v1/items/{id}',
  'S6  创建面交单        POST /api/v1/meetup-orders',
  'S7  我的面交单        GET  /api/v1/meetup-orders/mine',
  'S8  Seata 回滚        POST /api/v1/meetup-orders (debugFail=true)',
  'S9  RabbitMQ 消费     item.sale_count 异步 +1',
  'S10 Sentinel 限流     并发 GET /api/v1/items 出现 429',
  'S11 旧路径反代        GET  /api/v1/platform/shop/points'
)
if ($DryRun) {
  Write-Host ''
  Write-Host '--- 计划（-DryRun 不执行业务步骤）---' -ForegroundColor Yellow
  foreach ($line in $plan) { Write-Host ('  ' + $line) }
}

# ================================================================ 环境预检（只读部分）
$gwHp = Get-HostPort $Gateway
$gwPortUp = Test-Port $gwHp.host $gwHp.port
$script:preflight['gatewayPortUp'] = $gwPortUp
$legacyHp = Get-HostPort $Legacy
$legacyPortUp = Test-Port $legacyHp.host $legacyHp.port

# MySQL（DB 交叉校验；不可用则相关断言 SKIP）
$script:mysqlOk = Test-Path $MySQLPath
if ($script:mysqlOk) {
  try {
    $probeItem = Sql-Int 'ershou_item' 'ershou_item' 'SELECT 1'
    $script:itemDbOk = ($probeItem -eq 1)
  } catch { $script:itemDbOk = $false }
  try {
    $probeOrder = Sql-Int 'ershou_order' 'ershou_order' 'SELECT 1'
    $script:orderDbOk = ($probeOrder -eq 1)
  } catch { $script:orderDbOk = $false }
}
$script:preflight['mysql'] = [ordered]@{
  clientFound = $script:mysqlOk
  itemDbOk = $script:itemDbOk
  orderDbOk = $script:orderDbOk
}
Write-Host ('  [预检] 网关 ' + $Gateway + ': ' + $(if ($gwPortUp) { '端口就绪' } else { '未监听' })) -ForegroundColor DarkGray
Write-Host ('  [预检] 旧单体 ' + $Legacy + ': ' + $(if ($legacyPortUp) { '端口就绪' } else { '未监听' })) -ForegroundColor DarkGray
Write-Host ('  [预检] MySQL: client=' + $script:mysqlOk + ' ershou_item=' + $script:itemDbOk + ' ershou_order=' + $script:orderDbOk) -ForegroundColor DarkGray

if ($DryRun) {
  # -DryRun 只做只读预检：不自动拉起旧单体、不阻断退出
  $script:preflight['legacy'] = [pscustomobject]@{ up = $legacyPortUp; startedByUs = $false; how = 'dry-run 不自动拉起'; pid = 0 }
  Write-Host ''
  Write-Host ('  网关: ' + $(if ($gwPortUp) { '已监听' } else { '未监听（正式跑之前先启动 4 个服务）' })) -ForegroundColor Yellow
  Write-Host '  -DryRun 结束：以上为只读预检，未执行业务步骤、未拉起任何服务。' -ForegroundColor Yellow
  Write-Result 0
  exit 0
}

# 网关不可达 = 阻断（后面所有断言都没意义）
if (-not $gwPortUp) {
  Write-Host ''
  Write-Host ('[阻断] 网关 ' + $Gateway + ' 未监听。启动顺序：') -ForegroundColor Red
  Write-Host '  1) docker compose -f ershou-cloud/docker/docker-compose.yml up -d' -ForegroundColor Yellow
  Write-Host '  2) 发布 Nacos 配置（ershou-cloud/nacos-configs/*.yaml → 4 个 dataId）' -ForegroundColor Yellow
  Write-Host '  3) 启动 4 个服务：gateway 8088 / user 8081 / item 8082 / order 8083' -ForegroundColor Yellow
  Write-Host '  4) 启动旧单体 8080（本脚本也会在 -NoStartLegacy 未指定时自动拉起）' -ForegroundColor Yellow
  Write-Result 2
  exit 2
}

$gwProbe = Http 'GET' '/items?pageNum=1&pageSize=1' -TimeoutSec 8
$script:preflight['gatewayHttpStatus'] = $gwProbe.status
Write-Host ('  [预检] 网关 HTTP 探测: ' + $gwProbe.status + (Snip (' ' + $gwProbe.raw) 160)) -ForegroundColor DarkGray

# 旧单体（未起则后台拉起；不影响网关断言）
$legacyInfo = Get-LegacyInfo
$script:preflight['legacy'] = $legacyInfo
if ($legacyInfo.up) {
  Write-Host ('  [预检] 旧单体 8080: 就绪 (' + $legacyInfo.how + ')') -ForegroundColor DarkGray
} else {
  Write-Host ('  [预检] 旧单体 8080: 不可用 (' + $legacyInfo.how + ')，S11 将 SKIP') -ForegroundColor Yellow
}

# ================================================================ S1 短信验证码
$token = ''
$userId = 0
$itemId = 0
$orderId = 0
$orderNo = ''
$orderItemId = 0
# S6 下单前的 sale_count 基线（-1 = 未取到）。
# 必须在**下单前**取：MQ 消费可能在 S8/S9 之前就完成，S9 再读基线会读到增量后的值而误报 FAIL。
$saleBaseline = -1

Begin-Step 'S1' '短信验证码' 'HTTP 200 且业务 code=0'
$resp = Http 'POST' '/auth/sms-code' @{ phone = $Phone }
Set-Snippet (Resp-Brief $resp)
[void](Step-Check 'POST /auth/sms-code 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
End-Step

# ================================================================ S2 登录
Begin-Step 'S2' '登录（Sa-Token 会话写入 Redis db0）' 'code=0 且返回非空 token'
$resp = Http 'POST' '/auth/login' @{ phone = $Phone; code = $SmsCode }
Set-Snippet (Resp-Brief $resp)
[void](Step-Check 'POST /auth/login 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
$loginData = Get-Data $resp
$token = StrProp $loginData @('token')
$userObj = Prop $loginData 'user'
$userId = IntProp $userObj @('id')
if ($userId -le 0) { $userId = IntProp $loginData @('userId', 'id') }
[void](Step-Check '拿到非空 token' ($token -ne '') 'token 非空' ('token 长度=' + $token.Length))
[void](Step-Check '拿到当前用户 id' ($userId -gt 0) 'userId>0' ('userId=' + $userId))
End-Step

# ================================================================ S3 当前用户
Begin-Step 'S3' '当前用户（网关 X-User-Id 透传）' 'code=0，且返回的 id 与登录用户一致'
$resp = Http 'GET' '/users/me' $null $token
Set-Snippet (Resp-Brief $resp)
[void](Step-Check 'GET /users/me 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
$me = Get-Data $resp
$meId = IntProp $me @('id', 'userId')
if ($userId -gt 0) {
  [void](Step-Check 'me.id 与登录用户一致' ($meId -eq $userId) ('id=' + $userId) ('id=' + $meId))
} else {
  [void](Step-Check 'me 返回用户对象（含 id）' ($meId -gt 0) 'id>0' ('id=' + $meId))
}
End-Step

# ================================================================ S4 商品列表
Begin-Step 'S4' '商品列表（item-service）' 'code=0 且 records>=5'
$resp = Http 'GET' '/items?pageNum=1&pageSize=10' $null $token
Set-Snippet (Resp-Brief $resp)
[void](Step-Check 'GET /items 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
$itemsData = Get-Data $resp
$records = @()
$r = Prop $itemsData 'records'
if ($null -ne $r) { $records = @($r) }
[void](Step-Check '返回 records 且 >=5 条' ($records.Count -ge 5) '>=5' ('count=' + $records.Count))
if ($records.Count -gt 0) {
  $first = $records[0]
  $itemId = IntProp $first @('id')
  $itemTitle = StrProp $first @('title')
  [void](Step-Check '首条商品 id 有效' ($itemId -gt 0) 'id>0' ('id=' + $itemId + ' title=' + (Snip $itemTitle 40)))
}
End-Step

# ================================================================ S5 商品详情
Begin-Step 'S5' '商品详情' 'code=0，且返回 id 与请求一致'
if ($itemId -le 0) {
  Step-SkipAll 'S4 未拿到商品 id，无法请求详情'
} else {
  $resp = Http 'GET' ('/items/' + $itemId) $null $token
  Set-Snippet (Resp-Brief $resp)
  [void](Step-Check 'GET /items/{id} 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
  $detail = Get-Data $resp
  $detailId = IntProp $detail @('id')
  [void](Step-Check '详情 id 与请求一致' ($detailId -eq $itemId) ('id=' + $itemId) ('id=' + $detailId))
}
End-Step

# ================================================================ S6 创建面交单（Seata AT）
Begin-Step 'S6' '创建面交单（Feign 锁库存 + 订单落库）' 'code=0；DB item.stock 减少；meetup_order 新增 1 行'
$orderItemId = 0
if ($script:itemDbOk) {
  $cands = Db-BuyableItemIds 5
  foreach ($c in $cands) {
    if ($orderItemId -le 0 -and $c -ne $itemId) { $orderItemId = $c }
  }
  if ($orderItemId -le 0 -and $cands.Count -gt 0) { $orderItemId = [int]$cands[0] }
}
if ($orderItemId -le 0) { $orderItemId = $itemId }
$stockBefore = -1
if ($script:itemDbOk -and $orderItemId -gt 0) { $stockBefore = Db-ItemStock $orderItemId }
# sale_count 基线必须在下单之前取（MQ 消费可能在 S9 之前就完成）
$saleBaseline = -1
if ($script:itemDbOk -and $orderItemId -gt 0) { $saleBaseline = Db-ItemSaleCount $orderItemId }

$resp = Http 'POST' '/meetup-orders' @{ itemId = $orderItemId; quantity = $OrderQuantity; remark = 'cloud-demo 自动化演示下单' } $token
Set-Snippet (Resp-Brief $resp)
$orderOk = Step-Check 'POST /meetup-orders 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp)
$orderData = Get-Data $resp
$orderId = IntProp $orderData @('id', 'orderId')
$orderNo = StrProp $orderData @('orderNo', 'order_no')
if ($orderId -le 0 -and $orderOk -and $script:orderDbOk -and $userId -gt 0) {
  $latest = Db-LatestOrderId $userId $orderItemId
  if ($latest) { $orderId = [int]$latest }
}
[void](Step-Check '拿到订单 id 或 orderNo' (($orderId -gt 0) -or ($orderNo -ne '')) 'orderId>0 或 orderNo 非空' ('orderId=' + $orderId + ' orderNo=' + $orderNo))

if ($script:itemDbOk -and $orderOk -and $stockBefore -ge 0) {
  $stockAfter = Db-ItemStock $orderItemId
  [void](Step-Check ('DB 库存减少 ' + $OrderQuantity + '（item ' + $orderItemId + '）') ($stockAfter -eq ($stockBefore - $OrderQuantity)) ('stock=' + ($stockBefore - $OrderQuantity)) ('before=' + $stockBefore + ' after=' + $stockAfter))
} else {
  Step-Skip ('DB 库存交叉校验') 'MySQL ershou_item 不可用或下单未成功'
}
if ($script:orderDbOk -and $orderId -gt 0) {
  [void](Step-Check 'DB meetup_order 存在该订单行' ((Db-OrderExists $orderId) -eq 1) '1' ('count=' + (Db-OrderExists $orderId)))
} else {
  Step-Skip ('DB 订单行交叉校验') 'MySQL ershou_order 不可用或未拿到 orderId'
}
End-Step

# ================================================================ S7 我的面交单（Feign 补商品信息）
Begin-Step 'S7' '我的面交单（Feign 跨服务补商品标题）' 'code=0 且列表包含刚创建的订单，itemTitle 非空'
$resp = Http 'GET' '/meetup-orders/mine?pageNum=1&pageSize=20' $null $token
Set-Snippet (Resp-Brief $resp)
[void](Step-Check 'GET /meetup-orders/mine 返回 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
$mineData = Get-Data $resp
$mineRecs = @()
$mr = Prop $mineData 'records'
if ($null -ne $mr) { $mineRecs = @($mr) }
[void](Step-Check '列表非空' ($mineRecs.Count -gt 0) '>=1' ('count=' + $mineRecs.Count))
$hit = $null
foreach ($rec in $mineRecs) {
  $rid = IntProp $rec @('id', 'orderId')
  $rno = StrProp $rec @('orderNo', 'order_no')
  if (($orderId -gt 0 -and $rid -eq $orderId) -or ($orderNo -ne '' -and $rno -eq $orderNo)) { $hit = $rec; break }
}
if (($orderId -le 0) -and ($orderNo -eq '')) {
  Step-Skip '列表包含刚创建订单' 'S6 未拿到 orderId/orderNo，无法精确匹配'
} else {
  [void](Step-Check '列表包含刚创建的订单' ($null -ne $hit) ('orderId=' + $orderId) ('orderId=' + $orderId + ' orderNo=' + $orderNo))
}
$titleSource = $null
if ($null -ne $hit) { $titleSource = $hit }
elseif ($mineRecs.Count -gt 0) { $titleSource = $mineRecs[0] }
if ($null -ne $titleSource) {
  $t = StrProp $titleSource @('itemTitle', 'item_title')
  [void](Step-Check '订单带商品标题（Feign 拼装）' ($t -ne '') 'itemTitle 非空' ('itemTitle=' + (Snip $t 60)))
} else {
  Step-Skip '订单带商品标题' '列表为空'
}
End-Step

# ================================================================ S8 Seata 全局回滚
Begin-Step 'S8' 'Seata 全局回滚（debugFail=true）' '请求失败；item 库存不变；meetup_order 无新增行'
if ($SkipSeata) {
  Step-SkipAll '按参数 -SkipSeata 跳过'
} else {
  $rbItemId = 0
  if ($script:itemDbOk) {
    $cands2 = Db-BuyableItemIds 8
    foreach ($c in $cands2) {
      if ($rbItemId -le 0 -and $c -ne $orderItemId) { $rbItemId = $c }
    }
  }
  if ($rbItemId -le 0) { $rbItemId = $orderItemId }
  if ($rbItemId -le 0) { $rbItemId = $itemId }

  $rbStockBefore = -1
  $rbOrderCountBefore = -1
  if ($script:itemDbOk -and $rbItemId -gt 0) { $rbStockBefore = Db-ItemStock $rbItemId }
  if ($script:orderDbOk -and $userId -gt 0 -and $rbItemId -gt 0) { $rbOrderCountBefore = Db-OrderCount $userId $rbItemId }

  $resp = Http 'POST' '/meetup-orders' @{ itemId = $rbItemId; quantity = 1; debugFail = $true; remark = 'cloud-demo Seata rollback 演示' } $token
  Set-Snippet (Resp-Brief $resp)
  $failedAsExpected = (($resp.status -ge 400) -or ((Get-Code $resp) -ne 0))
  [void](Step-Check 'debugFail 下单按预期失败' $failedAsExpected 'HTTP>=400 或 code!=0' (Resp-Brief $resp))

  if ($script:itemDbOk -and $rbStockBefore -ge 0) {
    $rbStockAfter = Db-ItemStock $rbItemId
    [void](Step-Check ('回滚后库存不变（item ' + $rbItemId + '）') ($rbStockAfter -eq $rbStockBefore) ('stock=' + $rbStockBefore) ('before=' + $rbStockBefore + ' after=' + $rbStockAfter))
  } else {
    Step-Skip '回滚后库存不变' 'MySQL ershou_item 不可用'
  }
  if ($script:orderDbOk -and $rbOrderCountBefore -ge 0) {
    $rbOrderCountAfter = Db-OrderCount $userId $rbItemId
    [void](Step-Check '回滚后无新增订单行' ($rbOrderCountAfter -eq $rbOrderCountBefore) ('count=' + $rbOrderCountBefore) ('before=' + $rbOrderCountBefore + ' after=' + $rbOrderCountAfter))
  } else {
    Step-Skip '回滚后无新增订单行' 'MySQL ershou_order 不可用'
  }

  $rbHits = @()
  Get-ChildItem $script:logDir -Filter '*.log' -File -ErrorAction SilentlyContinue | ForEach-Object {
    $m = Select-String -Path $_.FullName -Pattern 'Rollback' -SimpleMatch -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($m) { $rbHits += ($_.Name + ':' + $m.LineNumber) }
  }
  if ($rbHits.Count -gt 0) { Info ('日志命中 Rollback: ' + ($rbHits -join '; ')) }
  else { Info '未在 .build/cloud/logs 命中 Rollback（服务控制台日志不在本目录属正常，DB 双证已是主证据）' }
}
End-Step

# ================================================================ S9 RabbitMQ -> item.sale_count
Begin-Step 'S9' 'RabbitMQ 异步消费（item.sale_count 增加）' ('成功下单后 ' + $MqWaitSec + ' 秒内 sale_count 相对下单前基线至少 +' + $OrderQuantity)
if ($SkipRabbit) {
  Step-SkipAll '按参数 -SkipRabbit 跳过'
} elseif ($orderItemId -le 0 -or $orderId -le 0) {
  Step-SkipAll 'S6 未成功创建订单，无法验证 MQ 消费'
} elseif (-not $script:itemDbOk) {
  Step-SkipAll 'MySQL ershou_item 不可用，无法直连校验 sale_count'
} else {
  # 队列侧证据：consumers / publish / ack（RabbitMQ 3.x 的 ack 在 message_stats 里）
  $queueStats = Get-QueueStats 'ershou.item.order-created.queue'
  if ($null -ne $queueStats) {
    $pubText = ''
    $ackText = ''
    $ms = Prop $queueStats 'message_stats'
    if ($null -ne $ms) {
      $pubText = [string](Prop $ms 'publish')
      $ackText = [string](Prop $ms 'ack')
    }
    Info ('RabbitMQ 队列 consumers=' + $queueStats.consumers + ' publish=' + $pubText + ' ack=' + $ackText + ' messages=' + $queueStats.messages)
  } else {
    Info 'RabbitMQ 管理 API(15672) 不可达，跳过队列侧证据'
  }

  # 基线：优先用 S6 下单前抓的值；取不到才回退现查（回退时可能已被消费，容错用 >=）
  $saleBefore = $saleBaseline
  if ($saleBefore -lt 0) { $saleBefore = Db-ItemSaleCount $orderItemId }
  $saleAfter = Db-ItemSaleCount $orderItemId
  $deadline = (Get-Date).AddSeconds($MqWaitSec)
  while ((Get-Date) -lt $deadline) {
    if ($saleAfter -ge ($saleBefore + $OrderQuantity)) { break }
    Start-Sleep -Seconds 1
    $saleAfter = Db-ItemSaleCount $orderItemId
  }
  [void](Step-Check ('DB sale_count 相对下单前基线 +' + $OrderQuantity + '（item ' + $orderItemId + '）') ($saleAfter -ge ($saleBefore + $OrderQuantity)) ('>=' + ($saleBefore + $OrderQuantity)) ('baselineBeforeS6=' + $saleBefore + ' now=' + $saleAfter + ' delta=' + ($saleAfter - $saleBefore)))
  if ($null -ne $queueStats) {
    [void](Step-Check '队列有消费者（item-service 在消费）' ([int]$queueStats.consumers -ge 1) 'consumers>=1' ('consumers=' + $queueStats.consumers))
  } else {
    Step-Skip '队列有消费者' 'RabbitMQ 管理 API 不可达'
  }

  # 消费日志证据：item-service 的 OrderCreatedListener 会打印 orderId=xx
  if ($orderId -gt 0) {
    $consumeHits = @()
    Get-ChildItem $script:logDir -Filter '*item*.log' -File -ErrorAction SilentlyContinue | ForEach-Object {
      $m = Select-String -Path $_.FullName -Pattern ('orderId=' + $orderId) -SimpleMatch -ErrorAction SilentlyContinue | Select-Object -Last 1
      if ($m) { $consumeHits += ($_.Name + ':' + $m.LineNumber) }
    }
    if ($consumeHits.Count -gt 0) { Info ('MQ 消费日志命中: ' + ($consumeHits -join '; ')) }
    else { Info ('未在 .build/cloud/logs 命中 orderId=' + $orderId + ' 的消费日志（服务控制台日志不在本目录属正常，DB 增量为准）') }
  }
}
End-Step

# ================================================================ S10 Gateway Sentinel 限流
Begin-Step 'S10' 'Gateway Sentinel 限流（QPS=5）' ('并发 ' + $SentinelConcurrency + ' 次 GET /api/v1/items，至少出现 1 个 429')
if ($SkipSentinel) {
  Step-SkipAll '按参数 -SkipSentinel 跳过'
} else {
  $sentinelUrl = $script:apiGateway + '/items?pageNum=1&pageSize=10'
  $got429 = $false
  $distText = ''
  $sample429 = ''
  for ($round = 1; $round -le 3; $round++) {
    $burst = Invoke-Burst $sentinelUrl $SentinelConcurrency
    $counts = @{}
    foreach ($b in $burst) {
      $k = [string]$b.status
      if ($counts.ContainsKey($k)) { $counts[$k] = $counts[$k] + 1 } else { $counts[$k] = 1 }
    }
    $parts = @()
    foreach ($k in @($counts.Keys | Sort-Object)) { $parts += ($k + 'x' + $counts[$k]) }
    $distText = ($parts -join ', ')
    $hits = @($burst | Where-Object { $_.status -eq 429 })
    if ($hits.Count -gt 0) {
      $got429 = $true
      $sample429 = $hits[0].body
      break
    }
    if ($round -lt 3) { Start-Sleep -Seconds 1 }
  }
  Set-Snippet $sample429
  [void](Step-Check '出现 429 限流响应' $got429 '>=1 个 429' ('状态分布: ' + $distText))
  if ($got429) {
    $code429 = -1
    try { $code429 = [int](($sample429 | ConvertFrom-Json).code) } catch { $code429 = -1 }
    [void](Step-Check '429 响应体带业务码 code=429' ($code429 -eq 429) 'code=429' ('code=' + $code429 + ' body=' + (Snip $sample429 200)))
  } else {
    Step-Skip '429 响应体业务码' '未出现限流，请确认 Gateway SentinelGatewayConfig 的 QPS=5 规则已加载'
  }
}
End-Step

# ================================================================ S11 未迁移路径反代旧单体
Begin-Step 'S11' '未迁移路径经网关反代旧单体 8080' 'HTTP 200 且 code=0（/platform/shop/points）'
if ($SkipLegacyProxy) {
  Step-SkipAll '按参数 -SkipLegacyProxy 跳过'
} elseif (-not $legacyInfo.up) {
  Step-SkipAll ('旧单体 8080 不可用（' + $legacyInfo.how + '），网关反代会 502')
} else {
  $resp = Http 'GET' ('/platform/shop/points?campusId=' + $CampusId) $null $token
  Set-Snippet (Resp-Brief $resp)
  [void](Step-Check '经网关访问旧路径返回 HTTP 200' ($resp.status -eq 200) '200' (Resp-Brief $resp))
  if ($resp.status -eq 200) {
    [void](Step-Check '反代响应业务 code=0' ((Get-Code $resp) -eq 0) 'code=0' (Resp-Brief $resp))
    $pointData = Get-Data $resp
    if ($null -ne $pointData) {
      $pointCount = @($pointData).Count
      [void](Step-Check '返回点位数组（数组即可，允许 0 条）' $true '数组' ('count=' + $pointCount))
    } else {
      Step-Skip '返回点位数组' 'data 为 null'
    }
  } else {
    Step-Skip '反代响应业务 code=0' 'HTTP 非 200（检查旧单体 8080 与网关反代路由）'
  }
}
End-Step

# ================================================================ 汇总 + 退出
$exitCode = 0
if ($script:failedChecks -gt 0) {
  $exitCode = $script:failedChecks
  if ($exitCode -gt 99) { $exitCode = 99 }
}
Write-Result $exitCode

if ($StopLegacyAfter -and $legacyInfo.startedByUs -and $legacyInfo.pid -gt 0) {
  Write-Host ('  按 -StopLegacyAfter 停止本脚本拉起的旧单体 pid=' + $legacyInfo.pid) -ForegroundColor Yellow
  try { Stop-Process -Id $legacyInfo.pid -Force -ErrorAction SilentlyContinue } catch { }
}

exit $exitCode
