<#
.SYNOPSIS
  第 4 轮 · T20 独立 QA 验收：移动端「平台严选」+ 开发期关闭商品限时（端到端证据脚本）。

.DESCRIPTION
  对应 `智能体团队/第4轮-移动端严选与开发期限时-设计.md` 第三节与第四节，以及 task-20：
    M0 静态门禁：T18（移动端严选）与 T19（后端限时开关）是否已交付；`.env`/SQL/jar/工具链/端口
    M1 DB 断言：status=2 >=16、status=7 =0、在售与平台商品 expire_at 全 NULL（只读 SELECT）
    M2 后端启动 + 限时关闭日志：java -jar 起 **独立验收端口 18080**（独立日志），等 >=35s 断言
      日志**含**「商品限时已关闭…跳过」且**不含**「件商品过期自动下架」
    M3 API 断言：GET /items >=16 且在售 expireAt=null；测试号发布 1 件 -> expireAt=null
      -> 下架 + 重新上架 -> 仍为 null；回归（列表/详情/搜索/地图/分类 + 平台 shop 5 个接口）
    M4 Flutter：flutter analyze（0 error）、flutter test（通过数）、flutter build apk --debug（耗时/产物）
    M5 真机截图（尽力而为）：adb reverse（手机 8080 -> 宿主 18080）+ adb install -r + 启动 App + screencap
      截图：首页（严选入口 + 官方代卖角标）、平台严选页（秒杀/券/代卖点/商品列表）
      导航用 `uiautomator dump` 按文本找节点并点按中心，不依赖固定坐标
    M6 汇总：结果 JSON + 退出码

  ⚠️ 本脚本只读源码/DB、只写 `.build/mobile/**`；**不改**源码/SQL/配置/文档。
     后端由本脚本在 **18080** 独占启动，默认结束时只 kill 自己的实例；
     **8080 是用户 IntelliJ IDEA 在跑的开发实例，绝不 kill、不 reverse 到它。**
     发现缺陷只报证据，不自己修。

  DryRun（后端未起也能跑）：
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-mobile-round4.ps1 -DryRun
    -> 只做 M0 静态门禁 + 打印 M1~M5 计划，不启动后端 / 不跑 Flutter / 不碰真机，退出码 0

  全量（Lead 放行后，需要：T18/T19 完成、Lead 已执行 sql/dev-relist-expired.sql、.env 已配
  ITEM_VALIDITY_ENABLED=false、18080 空闲）：
    powershell -ExecutionPolicy Bypass -File .\scripts\verify-mobile-round4.ps1 -BuildJar -JarAfter "2026-10-18 15:50"
    可选：
      -Port 18080            QA 实例端口（默认 18080；**不要改成 8080**，那是用户的 IDEA 实例）
      -SkipDevice            不碰真机（只跑后端/DB/Flutter）
      -SkipFlutter           不跑 Flutter（只跑后端/DB/真机）
      -SkipDb                不连 MySQL
      -BuildJar              jar 比源码旧时自动 mvn -DskipTests package（默认只报错不打包）
      -JarAfter "..."        额外断言 jar 构建时间晚于该时刻（本轮新包应晚于 2026-10-18 15:50）
      -ReuseExistingBackend  复用已在跑的 18080（日志断言会 SKIP）
      -KillExisting18080     **只** kill 占用 18080 的进程（默认不做；永不作用于 8080）
      -KeepBackend           跑完保留后端（默认 kill）
      -RestoreDevicePrefs    装完 App 后把真机 SharedPreferences 恢复成跑之前那份（默认保留 QA 配置）

  前置条件（缺一项会在 M0 报 FAIL，不会静默跳过）：
    1) T18/T19 代码已落地（本脚本静态检查文件与关键行）
    2) Lead 已执行 `sql/dev-relist-expired.sql`（DB 断言不通过会直接报）
    3) `.env` 已配 `ITEM_VALIDITY_ENABLED=false`（DotenvEnvironmentPostProcessor 会自动读项目根 .env）
    4) `ershou-server/target/ershou-server-1.0.0.jar` 比 T19 源码新（否则 -BuildJar 或先打包）
    5) 18080 空闲（8080 被用户 IDEA 占用是**正常现象**，本脚本不碰它）
    6) MySQL/Redis/MinIO/ES 已启动（M0 只 WARN；ES 挂了列表会回落 DB LIKE，不阻塞）

.NOTES
  退出码：0=全通过；2=后端不可用；3=登录失败；>0=失败断言数。
  产物：
    .build/mobile/logs/backend-*.out.log / *.err.log   后端日志（限时关闭断言的证据）
    .build/mobile/logs/flutter-*.log                    analyze/test/build 原始输出
    .build/mobile/logs/mvn-package.log                  -BuildJar 时的打包日志
    .build/mobile/*.png                                 真机截图（01 首页 / 02~04 平台严选）
    .build/mobile/backup/FlutterSharedPreferences.*.xml 真机配置备份
    .build/mobile/round4-verify-result.json             全部断言 + 证据 + 耗时
#>
param(
  [int]$Port = 18080,
  [string]$Base = '',
  [string]$ApiPrefix = '/api/v1',
  [string]$JarPath = 'ershou-server\target\ershou-server-1.0.0.jar',
  [string]$JarAfter = '',
  [string]$JavaExe = '',
  [string]$MvnExe = '',
  [string]$FlutterExe = '',
  [string]$AdbExe = '',
  [string]$MySQLPath = '',
  [string]$Phone = 'AQQB9X1526W02408',
  [string]$Pkg = 'com.ershou.ershou_app',
  [string]$Activity = 'com.ershou.ershou_app/.MainActivity',
  [string]$SmsCode = '123456',
  [string]$PublishPhone = '13800000001',
  [int]$CampusId = 2,
  [string]$ExpireCron = '*/10 * * * * ?',
  [int]$CronWaitSec = 40,
  [int]$StartupTimeoutSec = 180,
  [int]$AnalyzeTimeoutSec = 900,
  [int]$TestTimeoutSec = 900,
  [int]$BuildTimeoutSec = 1800,
  [string]$LogDir = '.build\mobile\logs',
  [string]$ShotDir = '.build\mobile',
  [string]$OutJson = '',
  [switch]$DryRun,
  [switch]$SkipDb,
  [switch]$SkipBackend,
  [switch]$SkipApi,
  [switch]$SkipFlutter,
  [switch]$SkipDevice,
  [switch]$BuildJar,
  [switch]$KillExisting18080,
  [switch]$ReuseExistingBackend,
  [switch]$KeepBackend,
  [switch]$RestoreDevicePrefs,
  [switch]$SkipJarFreshness
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
# 默认用独立端口 18080 起 QA 自己的实例：8080 是用户 IntelliJ IDEA 在跑的开发实例，**绝不动它**
$base = if ($Base) { $Base.TrimEnd('/') + $ApiPrefix } else { 'http://127.0.0.1:' + $Port + $ApiPrefix }
$logDirAbs = if ([System.IO.Path]::IsPathRooted($LogDir)) { $LogDir } else { Join-Path $root $LogDir }
$shotDirAbs = if ([System.IO.Path]::IsPathRooted($ShotDir)) { $ShotDir } else { Join-Path $root $ShotDir }
$backupDir = Join-Path $shotDirAbs 'backup'
if (-not (Test-Path $logDirAbs)) { New-Item -ItemType Directory -Force -Path $logDirAbs | Out-Null }
if (-not (Test-Path $shotDirAbs)) { New-Item -ItemType Directory -Force -Path $shotDirAbs | Out-Null }
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Force -Path $backupDir | Out-Null }
$outFull = if ($OutJson) { if ([System.IO.Path]::IsPathRooted($OutJson)) { $OutJson } else { Join-Path $root $OutJson } } else { Join-Path $shotDirAbs 'round4-verify-result.json' }

# ---------------------------------------------------------------- .env 载入（与仓库其它脚本同款）
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { return }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
}

# ---------------------------------------------------------------- 结果收集
$script:pass = 0; $script:fail = 0; $script:skip = 0; $script:warn = 0
$script:results = New-Object System.Collections.ArrayList
$script:evidence = New-Object System.Collections.ArrayList
function Check([string]$Id, [string]$Item, [bool]$Ok, [string]$Expect, [string]$Actual) {
  if ($Ok) { $script:pass++ } else { $script:fail++ }
  $status = if ($Ok) { 'PASS' } else { 'FAIL' }
  Write-Host ('  [' + $status + '] ' + $Id + ' ' + $Item) -ForegroundColor $(if ($Ok) { 'Green' } else { 'Red' })
  if (-not $Ok) { Write-Host ('         期望: ' + $Expect) -ForegroundColor Red; Write-Host ('         实际: ' + $Actual) -ForegroundColor Red }
  $null = $script:results.Add([pscustomobject]@{ id=$Id; item=$Item; status=$status; expect=$Expect; actual=$Actual })
}
function CheckContains([string]$Id, [string]$Item, [string]$Haystack, [string]$Needle) {
  Check $Id $Item ($Haystack.Contains($Needle)) ('包含 "' + $Needle + '"') ('未包含（长度 ' + $Haystack.Length + '）')
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
function Info([string]$Text) {
  Write-Host ('  [INFO] ' + $Text) -ForegroundColor DarkGray
  $null = $script:evidence.Add($Text)
}
function Head([string]$Text) { Write-Host ''; Write-Host ('=== ' + $Text + ' ===') -ForegroundColor Cyan }

# ---------------------------------------------------------------- 通用工具
function Read-TextShared([string]$path) {
  if (-not $path -or -not (Test-Path -LiteralPath $path)) { return '' }
  try {
    $fs = [System.IO.File]::Open($path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    $text = $sr.ReadToEnd()
    $sr.Close(); $fs.Close()
    return $text
  } catch {
    try { return (Get-Content -LiteralPath $path -Raw -Encoding UTF8) } catch { return '' }
  }
}
function FirstExisting([string]$preferred, [string[]]$fallbacks) {
  $all = @()
  if ($preferred) { $all += $preferred }
  if ($fallbacks) { $all += $fallbacks }
  foreach ($p in $all) { if ($p -and (Test-Path -LiteralPath $p)) { return (Resolve-Path -LiteralPath $p).Path } }
  return ''
}
function Get-PortOwner([int]$port) {
  try {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conn) { return $null }
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    $start = ''
    if ($proc) { try { $start = $proc.StartTime.ToString('yyyy-MM-dd HH:mm:ss') } catch { $start = '' } }
    return [pscustomobject]@{ pid = [int]$conn.OwningProcess; name = $(if ($proc) { $proc.ProcessName } else { '?' }); start = $start }
  } catch { return $null }
}
function Wait-PortFree([int]$port, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ($null -eq (Get-PortOwner $port)) { return $true }
    Start-Sleep -Milliseconds 500
  }
  return ($null -eq (Get-PortOwner $port))
}

# ---------------------------------------------------------------- HTTP（保留非 2xx body，便于取证）
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
  if ($raw -and $raw.TrimStart().StartsWith('{')) { try { $obj = $raw | ConvertFrom-Json } catch { $obj = $null } }
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
  $b = if ($resp.raw.Length -gt 600) { $resp.raw.Substring(0,600) + '...' } else { $resp.raw }
  return ('HTTP ' + $resp.status + ' ' + $b)
}
function CheckEnvelope([string]$Id, [string]$Item, $resp) {
  $code = EnvCode $resp
  Check $Id $Item ($code -eq 0) 'code=0' (RespBrief $resp)
  return ($code -eq 0)
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
  if ($null -eq $obj) { return $false }
  return ($null -ne $obj.PSObject.Properties[$name])
}

# ---------------------------------------------------------------- 原生进程（带日志 + 超时）
# 用 .NET Process 而不是 Start-Process：Windows PowerShell 5.1 里 Start-Process -PassThru + 重定向
# 拿不到 ExitCode（实测为空）→ 所有 exit=0 断言会静默失败。.NET Process 的 ExitCode 可靠。
function Invoke-NativeLogged([string]$exe, [string]$arguments, [string]$workDir, [string]$logBase, [int]$timeoutSec) {
  $outFile = $logBase + '.out.log'
  $errFile = $logBase + '.err.log'
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $out = ''; $err = ''; $exitCode = -997; $timedOut = $false; $errMsg = ''
  try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = $arguments
    $psi.WorkingDirectory = $workDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $null = $proc.Start()
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $finished = $proc.WaitForExit($timeoutSec * 1000)
    if (-not $finished) {
      $timedOut = $true
      try { $proc.Kill() } catch { }
      $exitCode = -999
    }
    try { $out = [string]$outTask.Result } catch { $out = '' }
    try { $err = [string]$errTask.Result } catch { $err = '' }
    if (-not $timedOut) {
      try { $exitCode = [int]$proc.ExitCode } catch { $exitCode = -996 }
    }
  } catch {
    $errMsg = $_.Exception.Message
    $exitCode = -998
  }
  $sw.Stop()
  try { [System.IO.File]::WriteAllText($outFile, $out, (New-Object System.Text.UTF8Encoding($false))) } catch { }
  try { [System.IO.File]::WriteAllText($errFile, $err, (New-Object System.Text.UTF8Encoding($false))) } catch { }
  return [pscustomobject]@{ exit = $exitCode; timedOut = $timedOut; seconds = [int]$sw.Elapsed.TotalSeconds; out = $outFile; err = $errFile; error = $errMsg }
}
function Get-NativeLog([string]$logBase) {
  return ((Read-TextShared ($logBase + '.out.log')) + "`n" + (Read-TextShared ($logBase + '.err.log')))
}
function Get-LogTail([string]$text, [int]$lines) {
  if (-not $text) { return '' }
  $all = $text -split "`r?`n"
  $start = [Math]::Max(0, $all.Count - $lines)
  return (($all[$start..($all.Count - 1)]) -join "`n")
}

# ---------------------------------------------------------------- 工具链解析
$toolchain = [ordered]@{}
function Resolve-Toolchain {
  $script:Java = FirstExisting $JavaExe @('C:\Program Files\Java\jdk-21\bin\java.exe', 'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe')
  $script:Mvn = FirstExisting $MvnExe @('D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd', 'C:\Program Files\apache-maven\bin\mvn.cmd')
  $script:Flutter = FirstExisting $FlutterExe @('E:\tools2\flutter\flutter_windows_3.44.3-stable\flutter\bin\flutter.bat', 'flutter.bat')
  $script:Adb = FirstExisting $AdbExe @('D:\tools\Android\Sdk\platform-tools\adb.exe', 'E:\tools2\scrcpy\adb.exe', 'adb.exe')
  $script:MySQL = FirstExisting $MySQLPath @('C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe')
  $script:Jar = if ([System.IO.Path]::IsPathRooted($JarPath)) { $JarPath } else { Join-Path $root $JarPath }
}
Resolve-Toolchain

# ---------------------------------------------------------------- MySQL（只读）
$script:mysqlArgs = @(
  ('--host=' + $(if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { '127.0.0.1' })),
  ('--port=' + $(if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' })),
  ('--user=' + $(if ($env:MYSQL_USER) { $env:MYSQL_USER } else { 'ershou' })),
  ('--password=' + $env:MYSQL_PASSWORD),
  ('--database=' + $(if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'ershou' })),
  '--default-character-set=utf8mb4', '-N', '-B'
)
function SqlRaw([string]$q) {
  $out = $q | & $script:MySQL @script:mysqlArgs 2>$null
  if ($LASTEXITCODE -ne 0) { throw ('mysql 退出码 ' + $LASTEXITCODE + '：' + $q) }
  return @($out)
}
function Sql1([string]$q) { $rows = @(SqlRaw $q); if ($rows.Count -eq 0) { return '' }; return ([string]$rows[0]).Trim() }
function SqlCount([string]$q) { $v = Sql1 $q; if (-not $v) { return 0 }; return [int]$v }

# ================================================================ 头部
Write-Host '============================================================' -ForegroundColor White
Write-Host ' 第 4 轮 · T20 QA 验收：移动端平台严选 + 开发期关闭商品限时' -ForegroundColor White
Write-Host (' 后端: ' + $base + '  校区: ' + $CampusId + '  真机: ' + $Phone) -ForegroundColor DarkGray
Write-Host (' 时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + '  模式: ' + $(if ($DryRun) { 'DryRun（静态门禁，不启动任何服务）' } else { '全量' })) -ForegroundColor DarkGray
Write-Host (' 产物: ' + $shotDirAbs + '  /  ' + $logDirAbs) -ForegroundColor DarkGray
Write-Host '============================================================' -ForegroundColor White

# ================================================================ M0 静态门禁
Head 'M0 静态门禁（T18/T19 交付物 / 环境 / 端口）'
$script:t18Ok = $false
$script:t19Ok = $false
$script:portOk = $false
$script:jarFresh = $false
$script:existingBackend = $null

# ---- M0-1 ~ M0-6 T19 后端限时开关
$itemServicePath = Join-Path $root 'ershou-server\src\main\java\com\ershou\campus\module\item\service\ItemService.java'
$scheduledPath = Join-Path $root 'ershou-server\src\main\java\com\ershou\campus\module\system\task\ScheduledTasks.java'
$appYmlPath = Join-Path $root 'ershou-server\src\main\resources\application.yml'
$relistSqlPath = Join-Path $root 'sql\dev-relist-expired.sql'
$itemServiceSrc = Read-TextShared $itemServicePath
$scheduledSrc = Read-TextShared $scheduledPath
$appYmlSrc = Read-TextShared $appYmlPath
$envSrc = Read-TextShared $envFile
$relistSqlSrc = Read-TextShared $relistSqlPath

$t19Checks = @(
  @{ id='M0-1'; item='ItemService 有 validityEnabled 开关（@Value 注入）'; ok=($itemServiceSrc.Contains('validityEnabled') -and $itemServiceSrc.Contains('isValidityEnabled')); expect='含 validityEnabled / isValidityEnabled' },
  @{ id='M0-2'; item='ItemService 发布路径按开关条件写 expireAt'; ok=($itemServiceSrc.Contains('setExpireAt(validityEnabled ?') -or $itemServiceSrc.Contains('validityEnabled ? LocalDateTime.now().plusDays(validDays) : null')); expect='setExpireAt(validityEnabled ? ... : null)' },
  @{ id='M0-3'; item='ItemService 重新上架按开关条件写 expireAt（wrapper 显式 set NULL）'; ok=($itemServiceSrc.Contains('expireAt = validityEnabled ?') -and $itemServiceSrc.Contains('Item::getExpireAt, expireAt')); expect='expireAt=validityEnabled?... 且 .set(Item::getExpireAt, expireAt)' },
  @{ id='M0-4'; item='ScheduledTasks.expireItems 有「商品限时已关闭…跳过」早退'; ok=($scheduledSrc.Contains('isValidityEnabled()') -and $scheduledSrc.Contains('商品限时已关闭')); expect='含 isValidityEnabled() 与 商品限时已关闭' },
  @{ id='M0-5'; item='application.yml 有 ershou.item.validity-enabled / expire-cron'; ok=($appYmlSrc.Contains('validity-enabled') -and $appYmlSrc.Contains('expire-cron') -and $appYmlSrc.Contains('ITEM_VALIDITY_ENABLED')); expect='含 validity-enabled / expire-cron / ITEM_VALIDITY_ENABLED' },
  @{ id='M0-6'; item='.env 已配 ITEM_VALIDITY_ENABLED=false（Lead 负责）'; ok=($envSrc -match 'ITEM_VALIDITY_ENABLED\s*=\s*false'); expect='ITEM_VALIDITY_ENABLED=false' },
  @{ id='M0-7'; item='sql/dev-relist-expired.sql 存在且含 status=7 重新上架 + 平台清 expire_at'; ok=((Test-Path -LiteralPath $relistSqlPath) -and ($relistSqlSrc.Contains('WHERE status = 7')) -and ($relistSqlSrc.Contains('seller_type = 2'))); expect='文件存在，含 WHERE status = 7 与 seller_type = 2' }
)
foreach ($c in $t19Checks) {
  Check $c.id $c.item ([bool]$c.ok) $c.expect $(if ($c.ok) { '已具备' } else { '缺失（T19 未交付/未完成）' })
  if ([bool]$c.ok) { } else { $script:t19Ok = $false }
}
$script:t19Ok = -not ($t19Checks | Where-Object { -not [bool]$_.ok })
Check 'M0-8' ('T19 静态门禁总判（7 项）') $script:t19Ok '全部通过' ($(if ($script:t19Ok) { '全部通过' } else { '存在缺失，见上' }))

# ---- M0-9 ~ M0-14 T18 移动端严选
$appRoot = Join-Path $root 'ershou_app'
$platformPagePath = Join-Path $appRoot 'lib\ui\pages\platform_page.dart'
$platformModelsPath = Join-Path $appRoot 'lib\data\platform_models.dart'
$platformRepoPath = Join-Path $appRoot 'lib\data\platform_repository.dart'
$homePagePath = Join-Path $appRoot 'lib\ui\pages\home_page.dart'
$itemCardPath = Join-Path $appRoot 'lib\ui\widgets\item_card.dart'
$modelsPath = Join-Path $appRoot 'lib\data\models.dart'
$platformPageSrc = Read-TextShared $platformPagePath
$homePageSrc = Read-TextShared $homePagePath
$itemCardSrc = Read-TextShared $itemCardPath
$modelsSrc = Read-TextShared $modelsPath
$t18Checks = @(
  @{ id='M0-9';  item='新增 platform_models.dart / platform_repository.dart / platform_page.dart'; ok=((Test-Path -LiteralPath $platformModelsPath) -and (Test-Path -LiteralPath $platformRepoPath) -and (Test-Path -LiteralPath $platformPagePath)); expect='3 个新文件都存在' },
  @{ id='M0-10'; item='platform_page.dart 四区块齐：平台严选/代卖点/限时秒杀/优惠券/严选好物'; ok=($platformPageSrc.Contains('平台严选') -and $platformPageSrc.Contains('代卖点') -and $platformPageSrc.Contains('限时秒杀') -and $platformPageSrc.Contains('优惠券') -and $platformPageSrc.Contains('严选好物')); expect='含 平台严选/代卖点/限时秒杀/优惠券/严选好物' },
  @{ id='M0-11'; item='home_page.dart 有「严选」入口且 import platform_page'; ok=($homePageSrc.Contains('严选') -and $homePageSrc.Contains('platform_page.dart') -and $homePageSrc.Contains('PlatformPage')); expect='含 严选 + platform_page.dart + PlatformPage' },
  @{ id='M0-12'; item='item_card.dart 平台商品金色「官方代卖」角标（isOfficial 判定）'; ok=($itemCardSrc.Contains('官方代卖') -and ($itemCardSrc.Contains('isOfficial') -or $itemCardSrc.Contains('sellerType'))); expect='含 官方代卖 + (isOfficial 或 sellerType)' },
  @{ id='M0-13'; item='models.dart 的 Item 新增 sellerType 字段 + isOfficial 判定'; ok=($modelsSrc.Contains('sellerType') -and $modelsSrc.Contains('isOfficial')); expect='含 sellerType + isOfficial' },
  @{ id='M0-14'; item='不动底部 4 tab / shell_page.dart 结构（仅提示）'; ok=$true; expect='提示项' }
)
foreach ($c in $t18Checks) { Check $c.id $c.item ([bool]$c.ok) $c.expect $(if ($c.ok) { '已具备' } else { '缺失（T18 未交付/未完成）' }) }
$script:t18Ok = -not ($t18Checks | Where-Object { -not [bool]$_.ok })
Check 'M0-15' ('T18 静态门禁总判（6 项）') $script:t18Ok '全部通过' ($(if ($script:t18Ok) { '全部通过' } else { '存在缺失，见上' }))

# ---- M0-16 ~ M0-19 工具链
$toolOk = $true
foreach ($pair in @(
    @{ name='java';   path=$script:Java },
    @{ name='flutter'; path=$script:Flutter },
    @{ name='adb';    path=$script:Adb },
    @{ name='mysql';  path=$script:MySQL })) {
  $ok = [bool]($pair.path -and (Test-Path -LiteralPath $pair.path))
  Check ('M0-' + (16 + @('java','flutter','adb','mysql').IndexOf($pair.name))) ($pair.name + ' 可用') $ok '存在' ($(if ($ok) { $pair.path } else { '未找到，可用参数指定' }))
  if (-not $ok) { $toolOk = $false }
}
$jarSizeMB = 0
if (Test-Path -LiteralPath $script:Jar) { $jarSizeMB = [math]::Round((Get-Item -LiteralPath $script:Jar).Length / 1MB, 1) }
Check 'M0-20' ('jar 存在: ' + $JarPath) (Test-Path -LiteralPath $script:Jar) '存在' $(if (Test-Path -LiteralPath $script:Jar) { ($jarSizeMB.ToString() + ' MB') } else { '不存在（需先 mvn package）' })

# ---- M0-21 jar 新鲜度（T19 源码/配置改过就必须重打包，否则测的是旧代码）
$script:jarFresh = $false
if (Test-Path -LiteralPath $script:Jar) {
  $jarItem = Get-Item -LiteralPath $script:Jar
  $srcFiles = @($itemServicePath, $scheduledPath, $appYmlPath, $envFile) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | ForEach-Object { Get-Item -LiteralPath $_ }
  $newest = $srcFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $script:jarFresh = ($jarItem.LastWriteTime -ge $newest.LastWriteTime)
  $detail = 'jar=' + $jarItem.LastWriteTime.ToString('MM-dd HH:mm:ss') + '；最新源码=' + $newest.LastWriteTime.ToString('MM-dd HH:mm:ss') + '（' + $newest.Name + '）'
  if ($script:jarFresh) {
    Check 'M0-21' 'jar 不旧于 T19 源码/配置（测的是新代码）' $true 'jar mtime >= 源码 mtime' $detail
  } elseif ($SkipJarFreshness) {
    Warn 'M0-21' 'jar 比源码旧，但传了 -SkipJarFreshness' $detail
  } elseif ($BuildJar) {
    Info ('jar 过期，-BuildJar 触发 mvn -DskipTests package：' + $detail)
  } else {
    Check 'M0-21' 'jar 不旧于 T19 源码/配置（测的是新代码）' $false 'jar mtime >= 源码 mtime（或传 -BuildJar）' $detail
  }
} else {
  Check 'M0-21' 'jar 新鲜度' $false 'jar 存在' 'jar 不存在'
}

# ---- M0-22 ~ M0-26 基础设施端口 + QA 验收端口（默认 18080）占用
foreach ($p in @(3306, 6380, 9010, 9211)) {
  $owner = Get-PortOwner $p
  if ($null -ne $owner) { Info ('依赖端口 ' + $p + ' 已监听（pid=' + $owner.pid + ' ' + $owner.name + '）') }
  else { Warn ('M0-' + (22 + @(3306,6380,9010,9211).IndexOf($p))) ('依赖端口 ' + $p + ' 未监听') 'MySQL/Redis/MinIO/ES 未全部启动：DB 断言会失败，ES 挂了列表回落 DB LIKE' }
}
# 8080 是用户 IDEA 开发实例：只记录、绝不动它
$ownerUser8080 = Get-PortOwner 8080
if ($null -ne $ownerUser8080) {
  Info ('8080 被占用（pid=' + $ownerUser8080.pid + ' ' + $ownerUser8080.name + '，启动于 ' + $ownerUser8080.start + '）—— 这是用户的 IDEA 开发实例，本脚本【绝不 kill、不 reverse 到它】，QA 走独立端口 ' + $Port)
} else {
  Info ('8080 当前空闲（用户 IDEA 实例未在跑）；本脚本仍使用独立端口 ' + $Port + '，不碰 8080')
}
$ownerQaPort = Get-PortOwner $Port
$script:existingBackend = $ownerQaPort
if ($null -eq $ownerQaPort) {
  $script:portOk = $true
  Check 'M0-27' ((($Port) -as [string]) + ' 空闲（QA 独占启动验收实例）') $true '空闲' '空闲'
} else {
  $desc = 'pid=' + $ownerQaPort.pid + ' ' + $ownerQaPort.name + ' 启动于 ' + $ownerQaPort.start
  if ($ReuseExistingBackend) {
    $script:portOk = $true
    Warn 'M0-27' (($Port -as [string]) + ' 已被占用，-ReuseExistingBackend 复用（启动参数/日志断言会 SKIP）') $desc
  } elseif ($KillExisting18080) {
    Info ('-KillExisting18080：结束占用 ' + $Port + ' 的进程 ' + $desc)
    try { Stop-Process -Id $ownerQaPort.pid -Force -ErrorAction Stop } catch { Warn 'M0-27a' ('kill ' + $Port + ' 占用进程失败') $_.Exception.Message }
    $script:portOk = Wait-PortFree $Port 15
    Check 'M0-27' (($Port -as [string]) + ' 已由 -KillExisting18080 释放') $script:portOk '15s 内释放' (Get-PortOwner $Port | ConvertTo-Json -Compress)
  } else {
    $script:portOk = $false
    Check 'M0-27' (($Port -as [string]) + ' 空闲（QA 独占启动验收实例）') $false '空闲' ('被占用：' + $desc + '；请先停掉，或 -ReuseExistingBackend / -KillExisting18080')
  }
}
# ---- M0-27b jar 构建时间下限（Lead 指定：本轮新包应晚于 15:50）
# 传了 -BuildJar 时 M0 阶段 jar 还是旧的，此处只提示；真正的断言在 M2.0 打包后（B1c）
if ($JarAfter -and (Test-Path -LiteralPath $script:Jar)) {
  try {
    $jarAfterDt = [datetime]::Parse($JarAfter)
    $jarMtime = (Get-Item -LiteralPath $script:Jar).LastWriteTime
    $jarAfterOk = ($jarMtime -gt $jarAfterDt)
    if ($jarAfterOk) {
      Check 'M0-27b' ('jar 构建时间晚于 ' + $JarAfter + '（实际 ' + $jarMtime.ToString('yyyy-MM-dd HH:mm:ss') + '）') $true ('>' + $JarAfter) ($jarMtime.ToString('yyyy-MM-dd HH:mm:ss'))
    } elseif ($BuildJar) {
      Info ('M0-27b 当前 jar 早于 ' + $JarAfter + '（' + $jarMtime.ToString('yyyy-MM-dd HH:mm:ss') + '），-BuildJar 会在 M2.0 重新打包后再断言')
    } else {
      Check 'M0-27b' ('jar 构建时间晚于 ' + $JarAfter + '（实际 ' + $jarMtime.ToString('yyyy-MM-dd HH:mm:ss') + '）') $false ('>' + $JarAfter + '（或传 -BuildJar）') ($jarMtime.ToString('yyyy-MM-dd HH:mm:ss'))
    }
  } catch { Warn 'M0-27b' 'JarAfter 解析失败，跳过构建时间下限断言' $_.Exception.Message }
} elseif ($JarAfter) {
  if ($BuildJar) { Info 'M0-27b jar 尚不存在，-BuildJar 会在 M2.0 生成后再断言' }
  else { Check 'M0-27b' 'jar 构建时间下限' $false 'jar 存在' 'jar 不存在' }
}

# ---- M0-28 DryRun 输出计划后退出（不启动后端/不跑 Flutter/不碰真机）
if ($DryRun) {
  Head 'DryRun：后续阶段计划（本次不执行）'
  @(
    'M1 DB 断言：status=2>=16、status=7=0、在售/平台商品 expire_at 全 NULL（只读 SELECT）',
    ('M2 后端：java -jar ' + $JarPath + ' --server.port=' + $Port + ' --ershou.item.expire-cron="' + $ExpireCron + '"；日志 ' + $logDirAbs),
    ('     等 ' + $CronWaitSec + 's 断言日志含「商品限时已关闭…跳过」且不含「件商品过期自动下架」'),
    'M3 API：GET /items>=16 且 expireAt=null；发布->下架->重上架 expireAt 仍 null；回归 + 平台 shop 5 接口',
    'M4 Flutter：flutter analyze（0 error）/ flutter test（通过数）/ flutter build apk --debug（耗时）',
    ('M5 真机：adb reverse tcp:8080->宿主' + $Port + ' + tcp:9010 -> adb install -r -> 启动 -> 截图首页/平台严选（uiautomator 文本定位点按）'),
    'M6 汇总：' + $outFull
  ) | ForEach-Object { Write-Host ('   - ' + $_) -ForegroundColor DarkGray }
  Write-Host ''
  Write-Host ('静态门禁：T19=' + $(if ($script:t19Ok) { 'PASS' } else { 'FAIL' }) + '，T18=' + $(if ($script:t18Ok) { 'PASS' } else { 'FAIL' }) + '，jar新鲜=' + $(if ($script:jarFresh) { 'PASS' } else { 'STALE' }) + '，' + $Port + '=' + $(if ($script:portOk) { 'FREE' } else { 'OCCUPIED' }) + '（8080 为用户 IDEA 实例，不参与判定）') -ForegroundColor Gray
  Write-Host 'DryRun 完成：未启动后端、未跑 Flutter、未操作真机（退出码 0）' -ForegroundColor Green
  exit 0
}

# ---- M0-28 门禁总判：T18/T19 必须交付；8080 必须可用
if (-not $script:t18Ok -or -not $script:t19Ok) {
  Write-Host ''
  Write-Host 'T18/T19 静态门禁未通过：本轮验收对象尚未交付，终止（避免产出假证据）。' -ForegroundColor Red
  exit 4
}

# ================================================================ M1 DB 断言
$script:dbInfo = [ordered]@{}
if ($SkipDb) {
  Head 'M1 DB 断言（-SkipDb）'
  Skip 'D1..D12' 'DB 断言' '传了 -SkipDb'
} elseif (-not $script:MySQL) {
  Head 'M1 DB 断言'
  Check 'D1' 'MySQL 客户端可用' $false '存在 mysql.exe' '未找到 mysql.exe（可用 -MySQLPath 指定）'
} else {
  Head 'M1 DB 断言（只读 SELECT；对应设计 1.2 / 第三节 1）'
  $connOk = $false
  try {
    $dbName = Sql1 'SELECT DATABASE();'
    Check 'D1' 'MySQL 可连接且库正确' ($dbName.Length -gt 0) '返回库名' $dbName
    $connOk = $true
  } catch {
    Check 'D1' 'MySQL 可连接' $false '能连上' ('异常: ' + $_.Exception.Message)
  }
  if (-not $connOk) {
    Check 'D2' 'DB 断言（连接失败后整组跳过）' $false 'MySQL 可用' '连接失败'
  } else {
    # D2 状态分布
    $distRows = @(SqlRaw "SELECT CONCAT_WS('|', status, seller_type, COUNT(*)) FROM item GROUP BY status, seller_type ORDER BY status, seller_type;")
    $dist = @()
    $statusCount = @{}
    foreach ($r in $distRows) {
      $f = ([string]$r) -split '\|'
      if ($f.Count -lt 3) { continue }
      $st = [int]$f[0]; $stype = [int]$f[1]; $cnt = [int]$f[2]
      if (-not $statusCount.ContainsKey($st)) { $statusCount[$st] = 0 }
      $statusCount[$st] = $statusCount[$st] + $cnt
      $dist += [pscustomobject]@{ status=$st; sellerType=$stype; count=$cnt }
      Info ('item 分布: status=' + $st + ' sellerType=' + $stype + ' count=' + $cnt)
    }
    $script:dbInfo['statusDistribution'] = $dist
    $onSale = if ($statusCount.ContainsKey(2)) { $statusCount[2] } else { 0 }
    $expired = if ($statusCount.ContainsKey(7)) { $statusCount[7] } else { 0 }
    $total = SqlCount 'SELECT COUNT(*) FROM item;'
    $script:dbInfo['total'] = $total
    $script:dbInfo['onSale'] = $onSale
    $script:dbInfo['expired'] = $expired
    Check 'D2' ('status=2 在售 >=16（实际 ' + $onSale + '；全库 ' + $total + ' 件）') ($onSale -ge 16) '>=16' $onSale.ToString()
    if ($onSale -eq 16) { Info 'status=2 正好 16 件（设计期望值：13 个人 + 3 平台）' } else { Warn 'D2b' ('status=2 不是 16（实际 ' + $onSale + '）') '若有 QA 临时商品会多 1；Lead 核验时注意' }
    Check 'D3' ('status=7 已过期 = 0（实际 ' + $expired + '）') ($expired -eq 0) '0' $expired.ToString()

    # D4 在售商品 expire_at 全 NULL
    $badOnSaleExpire = SqlCount 'SELECT COUNT(*) FROM item WHERE status = 2 AND expire_at IS NOT NULL;'
    Check 'D4' ('所有在售(status=2)商品 expire_at IS NULL（异常 ' + $badOnSaleExpire + ' 件）') ($badOnSaleExpire -eq 0) '0' $badOnSaleExpire.ToString()
    $onSaleNull = SqlCount 'SELECT COUNT(*) FROM item WHERE status = 2 AND expire_at IS NULL;'
    Info ('status=2 且 expire_at IS NULL = ' + $onSaleNull + ' / ' + $onSale)

    # D5 平台商品（seller_type=2, status in 2/4）expire_at 全 NULL
    $badPlatformExpire = SqlCount 'SELECT COUNT(*) FROM item WHERE seller_type = 2 AND status IN (2,4) AND expire_at IS NOT NULL;'
    Check 'D5' ('平台商品(status=2/4) expire_at IS NULL（异常 ' + $badPlatformExpire + ' 件）') ($badPlatformExpire -eq 0) '0' $badPlatformExpire.ToString()

    # D6 13 件被上架的个人商品：status=2 + expire_at NULL
    $personalOnSale = SqlCount 'SELECT COUNT(*) FROM item WHERE seller_type = 1 AND status = 2 AND expire_at IS NULL;'
    Check 'D6' ('个人(status=1)在售且 expire_at NULL >=13（实际 ' + $personalOnSale + '）') ($personalOnSale -ge 13) '>=13' $personalOnSale.ToString()
    $platformOnSale = SqlCount 'SELECT COUNT(*) FROM item WHERE seller_type = 2 AND status = 2;'
    Check 'D7' ('平台(seller_type=2)在售 >=3（实际 ' + $platformOnSale + '）') ($platformOnSale -ge 3) '>=3' $platformOnSale.ToString()

    # D8 被上架商品清单（证据）
    $relistedRows = @(SqlRaw "SELECT CONCAT_WS('|', id, title, seller_type, status, COALESCE(expire_at,'<NULL>'), published_at, updated_at) FROM item WHERE seller_type = 1 AND status = 2 ORDER BY id;")
    $relisted = @()
    foreach ($r in $relistedRows) {
      $f = ([string]$r) -split '\|'
      $relisted += [pscustomobject]@{ id=$f[0]; title=$f[1]; sellerType=$f[2]; status=$f[3]; expireAt=$f[4]; publishedAt=$f[5]; updatedAt=$f[6] }
      Info ('被上架个人商品 id=' + $f[0] + ' expire_at=' + $f[4] + ' published_at=' + $f[5] + ' title=' + $f[1])
    }
    $script:dbInfo['relistedPersonal'] = $relisted
    Check 'D8' ('被上架个人商品清单可取证（' + $relisted.Count + ' 件）') ($relisted.Count -ge 13) '>=13 件' $relisted.Count.ToString()

    # D9 SQL 执行新鲜度（Lead 执行 dev-relist 的痕迹：最近 updated_at）
    $maxUpdated = Sql1 'SELECT COALESCE(MAX(updated_at),"<NULL>") FROM item;'
    Info ('item 最近 updated_at = ' + $maxUpdated + '（应在 Lead 执行 sql/dev-relist-expired.sql 之后）')
    $script:dbInfo['maxUpdatedAt'] = $maxUpdated

    # D10 无表结构变更（信息性：只记录列数，不做阈值）
    $colCount = SqlCount "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item';"
    Info ('item 表列数 = ' + $colCount + '（设计 1.1 明确：本轮不改表结构）')
    $script:dbInfo['itemColumnCount'] = $colCount

    # D11 测试号存在（旧号，无新号发布限额）
    $publishUser = Sql1 ("SELECT CONCAT_WS('|', id, nickname, created_at, publish_limit) FROM sys_user WHERE phone_hash = SHA2('" + $PublishPhone + "', 256) LIMIT 1;")
    if ($publishUser) {
      Check 'D11' ('发布测试号存在：' + $PublishPhone + ' -> ' + (($publishUser -split '\|')[1])) $true '存在' $publishUser
      $script:dbInfo['publishUser'] = $publishUser
    } else {
      Warn 'D11' ('发布测试号不存在（首次登录会自动注册）: ' + $PublishPhone) '新号会受每日发布限额 5 条影响；如失败可换 -PublishPhone'
    }

    # D12 status=7 的 id 清单（应空）
    $expiredIds = Sql1 'SELECT COALESCE(GROUP_CONCAT(id), "") FROM item WHERE status = 7;'
    Check 'D12' ('不存在 status=7 商品（id 清单：' + $(if ($expiredIds) { $expiredIds } else { '空' }) + '）') (-not $expiredIds) '空' $expiredIds
  }
}

# ================================================================ M2 后端启动 + 限时关闭日志
$script:backendProc = $null
$script:backendLogBase = ''
$script:backendStarted = $false
$script:backendReadyMs = -1
$script:logText = ''

function Get-BackendLogText {
  if (-not $script:backendLogBase) { return '' }
  return (Get-NativeLog $script:backendLogBase)
}

Head ('M2 启动后端（独立验收端口 ' + $Port + '）+ 限时关闭日志断言')
if ($SkipBackend) {
  Skip 'B1..B6' '后端启动与日志断言' '传了 -SkipBackend'
} elseif ($ReuseExistingBackend -and ($null -ne $script:existingBackend)) {
  $probe = Http 'GET' '/categories'
  Check 'B1' ('复用已在跑的 ' + $Port + ' 后端可用') ((EnvCode $probe) -eq 0) 'code=0' (RespBrief $probe)
  Skip 'B2..B6' '启动/日志断言' '复用已有后端：没有本脚本的启动参数与日志文件'
} elseif (-not $script:portOk) {
  Check 'B1' ('后端可启动（' + $Port + ' 空闲）') $false ([string]$Port + ' 空闲') ([string]$Port + ' 被占用且未授权 kill/复用')
} elseif (-not (Test-Path -LiteralPath $script:Jar)) {
  Check 'B1' '后端可启动（jar 存在）' $false 'jar 存在' ('不存在：' + $script:Jar)
} else {
  # -BuildJar：jar 过期时重新打包
  $needBuild = $false
  if (-not $script:jarFresh -and -not $BuildJar) {
    Check 'B1' 'jar 新鲜（未传 -BuildJar，拒绝用旧 jar 产出假证据）' $false 'jar 不旧于源码' 'jar 比源码旧：请 -BuildJar 或先 mvn package'
    $script:backendStarted = $false
  } elseif (-not $script:jarFresh -and $BuildJar) {
    if (-not $script:Mvn) {
      Check 'B1' 'jar 需重打包（mvn 可用）' $false 'mvn 存在' '未找到 mvn（可用 -MvnExe 指定）'
    } else {
      $needBuild = $true
    }
  } else {
    Check 'B1' 'jar 新鲜（直接使用）' $true 'jar 不旧于源码' ((Get-Item -LiteralPath $script:Jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))
  }
  if (-not $script:jarFresh -and $BuildJar -and $script:Mvn) {
    Head 'M2.0 -BuildJar：mvn -DskipTests package'
    $serverDir = Join-Path $root 'ershou-server'
    if (-not $env:JAVA_HOME -and $script:Java) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $script:Java) }
    $mvnRes = Invoke-NativeLogged $script:Mvn '-DskipTests package' $serverDir (Join-Path $logDirAbs 'mvn-package') 900
    $mvnLog = Get-NativeLog (Join-Path $logDirAbs 'mvn-package')
    Check 'B1a' ('mvn package 成功（耗时 ' + $mvnRes.seconds + 's）') ($mvnRes.exit -eq 0) 'exit=0' ('exit=' + $mvnRes.exit + ' tail=' + (Get-LogTail $mvnLog 6))
    $script:jarFresh = (Test-Path -LiteralPath $script:Jar) -and ((Get-Item -LiteralPath $script:Jar).LastWriteTime -ge (Get-Item -LiteralPath $itemServicePath).LastWriteTime)
    Check 'B1b' '重打包后 jar 已刷新' $script:jarFresh 'jar mtime >= 源码 mtime' $(if (Test-Path -LiteralPath $script:Jar) { (Get-Item -LiteralPath $script:Jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') } else { 'jar 不存在' })
    if ($JarAfter -and $script:jarFresh) {
      try {
        $jarAfterDt = [datetime]::Parse($JarAfter)
        $jm = (Get-Item -LiteralPath $script:Jar).LastWriteTime
        Check 'B1c' ('新包构建时间晚于 ' + $JarAfter + '（实际 ' + $jm.ToString('yyyy-MM-dd HH:mm:ss') + '）') ($jm -gt $jarAfterDt) ('>' + $JarAfter) ($jm.ToString('yyyy-MM-dd HH:mm:ss'))
      } catch { Warn 'B1c' 'JarAfter 解析失败' $_.Exception.Message }
    }
  }

  if (($script:jarFresh -or $ReuseExistingBackend) -and ($script:portOk -or ($KillExisting18080 -and $null -eq (Get-PortOwner $Port)))) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $serverDir = Join-Path $root 'ershou-server'
    $script:backendLogBase = Join-Path $logDirAbs ('backend-' + $stamp)
    $logOut = $script:backendLogBase + '.out.log'
    $logErr = $script:backendLogBase + '.err.log'
    # 命令行参数：整串传给 Start-Process（含空格的 cron 必须带引号，否则 java 会收到多个参数）
    $argString = '-jar "' + $script:Jar + '"' +
      ' --server.port=' + $Port +
      ' --ershou.item.expire-cron="' + $ExpireCron + '"' +
      ' --logging.level.com.ershou.campus=debug'
    # Dotenv 候选路径从工作目录向上找：工作目录 = ershou-server -> ../.env 命中 E:\ershou\.env；
    # 同时显式设 ERSHOU_ENV_FILE 双保险（用户 8080 IDEA 实例不受影响，只作用于本进程）
    $env:ERSHOU_ENV_FILE = $envFile
    Info ('启动命令：' + $script:Java + ' ' + $argString)
    Info ('工作目录：' + $serverDir + '（Dotenv 会命中 E:\ershou\.env；并显式 ERSHOU_ENV_FILE=' + $envFile + '）')
    Info ('日志：' + $logOut + ' / ' + $logErr + '（独立端口 ' + $Port + '，不动用户 8080 实例）')
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
      $script:backendProc = Start-Process -FilePath $script:Java -ArgumentList $argString -WorkingDirectory $serverDir -RedirectStandardOutput $logOut -RedirectStandardError $logErr -PassThru -WindowStyle Hidden
      $script:backendStarted = $true
    } catch {
      Check 'B1' '后端进程启动' $false 'Start-Process 成功' $_.Exception.Message
    }
    if ($script:backendStarted) {
      Info ('后端 PID = ' + $script:backendProc.Id + '，等待就绪（最多 ' + $StartupTimeoutSec + 's）…')
      $ready = $false
      $deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
      $lastCode = -1
      while ((Get-Date) -lt $deadline) {
        $probe = Http 'GET' '/categories' $null '' 8
        $lastCode = EnvCode $probe
        if ($lastCode -eq 0) { $ready = $true; break }
        if ($script:backendProc.HasExited) { break }
        Start-Sleep -Seconds 2
      }
      $sw.Stop()
      $script:backendReadyMs = [int]$sw.Elapsed.TotalMilliseconds
      Check 'B1c' ('后端就绪（GET /categories code=0，耗时 ' + [int]($script:backendReadyMs / 1000) + 's）') $ready ('code=0，' + $StartupTimeoutSec + 's 内') ('code=' + $lastCode + '；tail=' + (Get-LogTail (Get-BackendLogText) 8))

      if ($ready) {
        # dotenv 载入确认
        $log0 = Get-BackendLogText
        $dotenvLine = ''
        foreach ($line in ($log0 -split "`r?`n")) { if ($line.Contains('[dotenv]')) { $dotenvLine = $line.Trim(); break } }
        if ($dotenvLine) {
          CheckContains 'B2' '后端启动日志确认已载入项目根 .env' $dotenvLine '.env'
          Info ('dotenv 日志：' + $dotenvLine)
        } else { Warn 'B2' '日志未出现 [dotenv] 行' '若 .env 未载入，ITEM_VALIDITY_ENABLED=false 不生效 → 限时断言会失败' }

        # 等 cron 至少触发 3 次（*/10s -> >=35s）
        $waitSec = [Math]::Max($CronWaitSec, 35)
        Info ('等待 ' + $waitSec + 's 让过期任务在 */10s cron 下至少触发 3 次…')
        Start-Sleep -Seconds $waitSec
        $logText = Get-BackendLogText
        $script:logText = $logText
        $logPathForReport = $script:backendLogBase
        $skipMatches = [regex]::Matches($logText, '商品限时已关闭')
        $skipLine = ''
        foreach ($line in ($logText -split "`r?`n")) { if ($line.Contains('商品限时已关闭')) { $skipLine = $line.Trim(); break } }
        $taskHits = ([regex]::Matches($logText, '\[task\]\[expire-item\]')).Count
        $downHits = ([regex]::Matches($logText, '件商品过期自动下架')).Count
        Check 'B3' ('日志出现「商品限时已关闭」（命中 ' + $skipMatches.Count + ' 次）') ($skipMatches.Count -ge 1) '>=1' ('0 次；日志=' + $(if ($logPathForReport) { $logPathForReport } else { '<无>' }))
        Check 'B4' ('日志含「跳过本轮」（限时关闭早退）') ($skipLine.Contains('跳过本轮')) '含 跳过本轮' ($(if ($skipLine) { $skipLine } else { '未命中' }))
        Check 'B5' ('日志不含「件商品过期自动下架」（命中 ' + $downHits + ' 次）') ($downHits -eq 0) '0 次' ($downHits.ToString() + ' 次')
        Check 'B6' ('[task][expire-item] 被 cron 触发 >=3 次（实际 ' + $taskHits + ' 次，cron=' + $ExpireCron + '）') ($taskHits -ge 3) '>=3' $taskHits.ToString()
        $firstSkipAt = ''
        if ($skipLine -and $skipLine -match '^\d{4}-\d{2}-\d{2}[ T]') { $firstSkipAt = $skipLine.Substring(0, 19) }
        $script:backendInfo = [ordered]@{
          pid = $script:backendProc.Id
          jar = $script:Jar
          cron = $ExpireCron
          logBase = $script:backendLogBase
          readyMs = $script:backendReadyMs
          skipHits = $skipMatches.Count
          downHits = $downHits
          taskHits = $taskHits
          firstSkipLine = $skipLine
        }
        # 启动期 ERROR 扫描（WARN）
        $errHits = ([regex]::Matches($logText, '(?m)^.*\bERROR\b.*$')).Count
        if ($errHits -gt 0) { Warn 'B7' ('后端日志出现 ' + $errHits + ' 行 ERROR（需人工确认是否影响验收）') (Get-LogTail $logText 6) }
        else { Info '后端日志无 ERROR 行' }
      }
    }
  }
}

# ================================================================ M3 API 断言
$script:apiInfo = [ordered]@{}
Head 'M3 API 断言（对应设计第三节 1/3、第四节 2/3）'
if ($SkipApi) {
  Skip 'A..' 'API 断言' '传了 -SkipApi'
} elseif ($null -eq $script:backendProc -and -not $ReuseExistingBackend) {
  Check 'A0' 'API 断言（后端未就绪）' $false '后端已启动' '跳过 API 组'
} else {
  # A1 列表：>=16 条且在售 expireAt 全 null（ES 索引刚启动可能还在重建，轮询等收敛）
  $maxWait = 180
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $listResp = $null
  $records = @()
  $total = 0
  while ($sw.Elapsed.TotalSeconds -lt $maxWait) {
    $listResp = Http 'GET' '/items?pageNum=1&pageSize=100' $null '' 25
    if ((EnvCode $listResp) -eq 0) {
      $data = Prop $listResp.obj 'data'
      $records = @(Prop $data 'records')
      $total = IntProp $data @('total')
      if ($records.Count -ge 16) { break }
    }
    Start-Sleep -Seconds 5
  }
  $sw.Stop()
  $listOk = CheckEnvelope 'A1-1' 'GET /items?pageNum=1&pageSize=100 code=0' $listResp
  if ($listOk) {
    Check 'A1-2' ('列表返回 >=16 条（records=' + $records.Count + ', total=' + $total + '，等待 ' + [int]$sw.Elapsed.TotalSeconds + 's）') (($records.Count -ge 16) -or ($total -ge 16)) 'records>=16 或 total>=16' ('records=' + $records.Count + ',total=' + $total)
    $badExpire = @($records | Where-Object { $null -ne (Prop $_ 'expireAt') })
    Check 'A1-3' ('列表内商品 expireAt 全为 null（异常 ' + $badExpire.Count + ' 条）') ($badExpire.Count -eq 0) '0 条' ($badExpire.Count.ToString() + ' 条非 null')
    $platformInList = @($records | Where-Object { (IntProp $_ @('sellerType')) -eq 2 })
    Check 'A1-4' ('列表混排出现平台商品 sellerType=2 >=3（实际 ' + $platformInList.Count + ' 条）') ($platformInList.Count -ge 3) '>=3' $platformInList.Count.ToString()
    $personalInList = @($records | Where-Object { (IntProp $_ @('sellerType')) -eq 1 })
    Check 'A1-5' ('列表出现个人商品 sellerType=1（实际 ' + $personalInList.Count + ' 条，含 13 件被上架）') ($personalInList.Count -ge 13) '>=13' $personalInList.Count.ToString()
    $sample = @()
    foreach ($rec in ($records | Select-Object -First 5)) {
      $sample += [pscustomobject]@{ id=(IntProp $rec @('id')); title=(StrProp $rec @('title')); sellerType=(IntProp $rec @('sellerType')); status=(IntProp $rec @('status')); expireAt=(Prop $rec 'expireAt') }
    }
    $script:apiInfo['listSample'] = $sample
    $script:apiInfo['listRecords'] = $records.Count
    $script:apiInfo['listTotal'] = $total
    # DB 与 ES 口径（信息性）
    if (-not $SkipDb -and $script:dbInfo['onSale']) {
      if ([int]$script:dbInfo['onSale'] -eq $total) { Info ('ES total(' + $total + ') == DB status=2(' + $script:dbInfo['onSale'] + ')，索引已收敛') }
      else { Warn 'A1-6' ('ES total(' + $total + ') != DB status=2(' + $script:dbInfo['onSale'] + ')') 'ES 索引可能未收敛（增量同步 2 分钟一轮）；不影响 >=16 断言语义' }
    }
    # 平台官方商品详情（id 888 是种子平台商品；失败则从列表里找第一个 sellerType=2）
    $platformId = 888
    $platformRec = @($records | Where-Object { (IntProp $_ @('sellerType')) -eq 2 } | Select-Object -First 1)
    if ($platformRec.Count -gt 0) { $platformId = IntProp $platformRec[0] @('id') }
    $detailResp = Http 'GET' ('/items/' + $platformId)
    if (CheckEnvelope 'A1-7' ('GET /items/' + $platformId + ' 平台商品详情 code=0') $detailResp) {
      $d = Prop $detailResp.obj 'data'
      Check 'A1-8' ('平台商品 sellerType=2（实际 ' + (IntProp $d @('sellerType')) + '）') ((IntProp $d @('sellerType')) -eq 2) '2' (IntProp $d @('sellerType')).ToString()
      Check 'A1-9' ('平台商品 expireAt 为 null（实际 ' + (StrProp $d @('expireAt')) + '）') ($null -eq (Prop $d 'expireAt')) 'null' ([string](Prop $d 'expireAt'))
    }
  }

  # A2 发布 -> expireAt null -> 下架 -> 重新上架 -> 仍 null（设计第三节 1）
  Head 'M3.5 发布 / 下架 / 重新上架：expireAt 必须为 null'
  $script:testItemId = 0
  $loginOk = $false
  try {
    $r1 = Http 'POST' '/auth/sms-code' @{ phone = $PublishPhone }
    Check 'A2-1' ('发送 mock 验证码 phone=' + $PublishPhone) ((EnvCode $r1) -eq 0) 'code=0' (RespBrief $r1)
    $r2 = Http 'POST' '/auth/login' @{ phone = $PublishPhone; code = $SmsCode; schoolId = 1; campusId = $CampusId; deviceId = ('qa-round4-' + $PublishPhone) }
    $loginOk = (EnvCode $r2) -eq 0
    Check 'A2-2' '登录测试号成功' $loginOk 'code=0' (RespBrief $r2)
    if ($loginOk) {
      $loginData = Prop $r2.obj 'data'
      $script:publishToken = StrProp $loginData @('token')
      $script:publishUserId = IntProp (Prop $loginData 'user') @('id')
      Info ('测试号 userId=' + $script:publishUserId + ' token 前 8 位=' + $(if ($script:publishToken.Length -ge 8) { $script:publishToken.Substring(0,8) } else { $script:publishToken }) + '…')
    }
  } catch {
    Check 'A2-2' '登录测试号' $false '登录成功' $_.Exception.Message
  }
  if (-not $loginOk) {
    Write-Host ''
    Write-Host '登录失败：无法验证发布/重上架链路，终止（退出码 3）。' -ForegroundColor Red
    if ($script:backendStarted -and -not $KeepBackend) { try { Stop-Process -Id $script:backendProc.Id -Force } catch { } }
    exit 3
  }

  # 选一个可用的 preferPointId（没有就不传，publish 允许为空）
  $preferPointId = 0
  if (-not $SkipDb) {
    try { $preferPointId = SqlCount 'SELECT id FROM trade_point WHERE campus_id = 2 AND status = 1 ORDER BY id LIMIT 1;' } catch { $preferPointId = 0 }
  }
  $publishBody = [ordered]@{
    title = 'QA第4轮验收商品-限时关闭'
    description = '第4轮 QA 自动化发布：验证开发期限时关闭后发布商品不写 expire_at。脚本会自动下架，不进演示数据。'
    categoryId = 1
    conditionLevel = 2
    price = 1.23
    images = @('http://127.0.0.1:9010/ershou/test-qa-round4.png')
    stock = 1
    isUnique = 1
    negotiable = 1
  }
  if ($preferPointId -gt 0) { $publishBody['preferPointId'] = $preferPointId }
  $pub = Http 'POST' '/items' $publishBody $script:publishToken
  if (CheckEnvelope 'A2-3' 'POST /items 发布测试商品 code=0' $pub) {
    $pubData = Prop $pub.obj 'data'
    $script:testItemId = IntProp $pubData @('itemId')
    $pubStatus = IntProp $pubData @('status')
    Check 'A2-4' ('发布返回 itemId>0（id=' + $script:testItemId + '，status=' + $pubStatus + '）') ($script:testItemId -gt 0) 'itemId>0' $script:testItemId.ToString()
    Check 'A2-5' ('发布后直接为在售 status=2（未命中可疑词转人工）') ($pubStatus -eq 2) '2' $pubStatus.ToString()
    $detail1 = Http 'GET' ('/items/' + $script:testItemId)
    if (CheckEnvelope 'A2-6' ('GET /items/' + $script:testItemId + ' 详情 code=0') $detail1) {
      $d1 = Prop $detail1.obj 'data'
      Check 'A2-7' ('发布后 expireAt 为 null（实际 ' + (StrProp $d1 @('expireAt')) + '）') ($null -eq (Prop $d1 'expireAt')) 'null' ([string](Prop $d1 'expireAt'))
      Check 'A2-8' ('发布后 status=2（实际 ' + (IntProp $d1 @('status')) + '）') ((IntProp $d1 @('status')) -eq 2) '2' (IntProp $d1 @('status')).ToString()
      $script:apiInfo['publish'] = [ordered]@{ itemId=$script:testItemId; status=(IntProp $d1 @('status')); expireAt=(Prop $d1 'expireAt') }
    }
    if (-not $SkipDb) {
      try {
        $dbExpire = Sql1 ('SELECT COALESCE(expire_at, "<NULL>") FROM item WHERE id = ' + $script:testItemId + ';')
        Check 'A2-9' ('DB 落库 expire_at IS NULL（实际 ' + $dbExpire + '）') ($dbExpire -eq '<NULL>') '<NULL>' $dbExpire
      } catch { Warn 'A2-9' 'DB 交叉校验发布商品 expire_at 失败' $_.Exception.Message }
    }
    # 下架
    $off = Http 'POST' ('/items/' + $script:testItemId + '/offline') $null $script:publishToken
    Check 'A2-10' ('POST /items/' + $script:testItemId + '/offline 下架 code=0') ((EnvCode $off) -eq 0) 'code=0' (RespBrief $off)
    $detail2 = Http 'GET' ('/items/' + $script:testItemId)
    if ((EnvCode $detail2) -eq 0) {
      $d2 = Prop $detail2.obj 'data'
      Check 'A2-11' ('下架后 status=5（实际 ' + (IntProp $d2 @('status')) + '）') ((IntProp $d2 @('status')) -eq 5) '5' (IntProp $d2 @('status')).ToString()
      Check 'A2-12' ('下架后 expireAt 仍为 null（实际 ' + (StrProp $d2 @('expireAt')) + '）') ($null -eq (Prop $d2 'expireAt')) 'null' ([string](Prop $d2 'expireAt'))
    }
    # 重新上架
    $rel = Http 'POST' ('/items/' + $script:testItemId + '/relist') $null $script:publishToken
    if (CheckEnvelope 'A2-13' ('POST /items/' + $script:testItemId + '/relist 重新上架 code=0') $rel) {
      $relData = Prop $rel.obj 'data'
      Check 'A2-14' ('重新上架返回 status=2（实际 ' + (IntProp $relData @('status')) + '）') ((IntProp $relData @('status')) -eq 2) '2' (IntProp $relData @('status')).ToString()
    }
    $detail3 = Http 'GET' ('/items/' + $script:testItemId)
    if (CheckEnvelope 'A2-15' ('GET /items/' + $script:testItemId + ' 重新上架后详情 code=0') $detail3) {
      $d3 = Prop $detail3.obj 'data'
      Check 'A2-16' ('重新上架后 expireAt 仍为 null（实际 ' + (StrProp $d3 @('expireAt')) + '）') ($null -eq (Prop $d3 'expireAt')) 'null' ([string](Prop $d3 'expireAt'))
      Check 'A2-17' ('重新上架后 status=2（实际 ' + (IntProp $d3 @('status')) + '）') ((IntProp $d3 @('status')) -eq 2) '2' (IntProp $d3 @('status')).ToString()
      $script:apiInfo['relist'] = [ordered]@{ itemId=$script:testItemId; status=(IntProp $d3 @('status')); expireAt=(Prop $d3 'expireAt') }
    }
    if (-not $SkipDb) {
      try {
        $dbExpire2 = Sql1 ('SELECT COALESCE(expire_at, "<NULL>") FROM item WHERE id = ' + $script:testItemId + ';')
        Check 'A2-18' ('DB 重新上架后 expire_at IS NULL（实际 ' + $dbExpire2 + '）') ($dbExpire2 -eq '<NULL>') '<NULL>' $dbExpire2
      } catch { Warn 'A2-18' 'DB 交叉校验重新上架 expire_at 失败' $_.Exception.Message }
    }
    # 清理：下架 QA 临时商品（不留在演示列表里；本脚本只通过 API 改状态，不写 SQL）
    $cleanup = Http 'POST' ('/items/' + $script:testItemId + '/offline') $null $script:publishToken
    $cleanupOk = (EnvCode $cleanup) -eq 0
    Check 'A2-19' 'QA 临时商品已下架（不污染 status=2 演示数据）' $cleanupOk 'code=0' (RespBrief $cleanup)
    if (-not $cleanupOk) { Warn 'A2-19b' ('QA 临时商品 id=' + $script:testItemId + ' 未下架，请 Lead 手工处理') '' }
  }

  # A3 回归
  Head 'M3.9 回归：列表/详情/搜索/地图/分类 + 平台 shop 5 接口'
  $regOk = @()
  foreach ($probe in @(
      @{ id='A3-1'; name='GET /items 列表'; path='/items?pageNum=1&pageSize=20' },
      @{ id='A3-2'; name='GET /categories 分类'; path='/categories' },
      @{ id='A3-3'; name='GET /items?keyword=严选 搜索'; path='/items?keyword=' + [uri]::EscapeDataString('严选') + '&pageNum=1&pageSize=10' },
      @{ id='A3-4'; name='GET /search/status 搜索状态'; path='/search/status' },
      @{ id='A3-5'; name='GET /map/points 地图点位'; path='/map/points?campusId=' + $CampusId },
      @{ id='A3-6'; name='GET /trade-points 面交点'; path='/trade-points?campusId=' + $CampusId })) {
    $resp = Http 'GET' $probe.path
    $ok = CheckEnvelope $probe.id ($probe.name + ' code=0') $resp
    $regOk += $ok
    if ($probe.id -eq 'A3-4' -and $ok) {
      $sd = Prop $resp.obj 'data'
      Info ('搜索状态: enabled=' + (StrProp $sd @('enabled')) + ' esAvailable=' + (StrProp $sd @('esAvailable')) + ' reindexing=' + (StrProp $sd @('reindexing')) + ' docCount=' + (IntProp $sd @('docCount')) + ' onSaleCount=' + (IntProp $sd @('onSaleCount')))
      $script:apiInfo['searchStatus'] = $sd
      if ((StrProp $sd @('esAvailable')) -eq 'True' -and (IntProp $sd @('onSaleCount')) -lt 16) { Warn 'A3-4b' ('ES onSaleCount=' + (IntProp $sd @('onSaleCount')) + ' < 16') 'ES 索引可能还没收敛（增量同步每 2 分钟；列表断言以 /items 实测为准）' }
    }
  }
  $shopProbes = @(
    @{ id='A3-7';  name='GET /platform/shop/overview'; path='/platform/shop/overview?campusId=' + $CampusId },
    @{ id='A3-8';  name='GET /platform/shop/points'; path='/platform/shop/points?campusId=' + $CampusId },
    @{ id='A3-9';  name='GET /platform/shop/items'; path='/platform/shop/items?pageNum=1&pageSize=12&campusId=' + $CampusId },
    @{ id='A3-10'; name='GET /platform/shop/coupons'; path='/platform/shop/coupons?campusId=' + $CampusId },
    @{ id='A3-11'; name='GET /platform/shop/flash-sale'; path='/platform/shop/flash-sale?campusId=' + $CampusId })
  $shopOkCount = 0
  foreach ($sp in $shopProbes) {
    $resp = Http 'GET' $sp.path
    $ok = CheckEnvelope $sp.id ($sp.name + ' code=0') $resp
    if ($ok) { $shopOkCount++ }
    if ($ok -and $sp.id -eq 'A3-7') {
      $ov = Prop $resp.obj 'data'
      Check 'A3-7b' ('overview.platformItemCount >=3（实际 ' + (IntProp $ov @('platformItemCount')) + '）') ((IntProp $ov @('platformItemCount')) -ge 3) '>=3' (IntProp $ov @('platformItemCount')).ToString()
    }
    if ($ok -and $sp.id -eq 'A3-8') {
      $pts = @(Prop $resp.obj 'data')
      Check 'A3-8b' ('代卖点 >=3（实际 ' + $pts.Count + '）') ($pts.Count -ge 3) '>=3' $pts.Count.ToString()
    }
    if ($ok -and $sp.id -eq 'A3-9') {
      $pd = Prop $resp.obj 'data'
      $pitems = @(Prop $pd 'records')
      Check 'A3-9b' ('平台商品分页 >=3（records=' + $pitems.Count + '）') ($pitems.Count -ge 3) '>=3' $pitems.Count.ToString()
      $badS = @($pitems | Where-Object { (IntProp $_ @('sellerType')) -ne 2 })
      Check 'A3-9c' '平台商品分页 sellerType 全 2' ($badS.Count -eq 0) '0 条异常' ($badS.Count.ToString() + ' 条异常')
    }
    if ($ok -and $sp.id -eq 'A3-10') {
      $cps = @(Prop $resp.obj 'data')
      Check 'A3-10b' ('优惠券列表 >=1 张（实际 ' + $cps.Count + '）') ($cps.Count -ge 1) '>=1' $cps.Count.ToString()
    }
    if ($ok -and $sp.id -eq 'A3-11') {
      $fs = Prop $resp.obj 'data'
      if ($null -eq $fs) { Warn 'A3-11b' '当前无进行中秒杀场次（data=null，接口本身 OK）' '平台页秒杀区块会隐藏（设计 2.2 允许）' }
      else { Info ('秒杀场次 id=' + (IntProp $fs @('id')) + ' status=' + (IntProp $fs @('status')) + ' items=' + (@(Prop $fs 'items')).Count) }
    }
  }
  Check 'A3-12' ('平台 shop 5 个接口全部 200/code=0（实际 ' + $shopOkCount + '/5）') ($shopOkCount -eq 5) '5/5' ($shopOkCount.ToString() + '/5')
}

# ================================================================ M4 Flutter
$script:flutterInfo = [ordered]@{}
Head 'M4 Flutter：analyze / test / build apk --debug'
if ($SkipFlutter) {
  Skip 'F1..F6' 'Flutter 检查' '传了 -SkipFlutter'
} elseif (-not $script:Flutter) {
  Check 'F0' 'flutter 可用' $false '存在 flutter.bat' '未找到 flutter（可用 -FlutterExe 指定）'
} else {
  $appDir = Join-Path $root 'ershou_app'
  # F1 analyze
  $anRes = Invoke-NativeLogged $script:Flutter 'analyze' $appDir (Join-Path $logDirAbs 'flutter-analyze') $AnalyzeTimeoutSec
  $anLog = Get-NativeLog (Join-Path $logDirAbs 'flutter-analyze')
  if ($anRes.timedOut) {
    Check 'F1' ('flutter analyze 完成（' + $AnalyzeTimeoutSec + 's 内）') $false '未超时' ($AnalyzeTimeoutSec.ToString() + 's 超时')
  } else {
    # 本机 Flutter 3.44 的格式是 `   info - ...` / `   warning - ...` / `   error - ...`（不是 •）
    $errLines = @($anLog -split "`r?`n" | Where-Object { $_ -match '^\s*error\s+(-|•)\s' })
    $warnLines = @($anLog -split "`r?`n" | Where-Object { $_ -match '^\s*warning\s+(-|•)\s' })
    $infoLines = @($anLog -split "`r?`n" | Where-Object { $_ -match '^\s*info\s+(-|•)\s' })
    $issuesLine = ''
    foreach ($line in ($anLog -split "`r?`n")) { if ($line -match 'issues? found|No issues found') { $issuesLine = $line.Trim(); break } }
    Check 'F1' ('flutter analyze 完成（耗时 ' + $anRes.seconds + 's，exit=' + $anRes.exit + '）') $true '未超时且产出报告' ('exit=' + $anRes.exit)
    Check 'F2' ('flutter analyze 0 error（实际 ' + $errLines.Count + ' 个 error）') ($errLines.Count -eq 0) '0' $errLines.Count.ToString()
    Check 'F2b' ('flutter analyze 0 warning（实际 ' + $warnLines.Count + ' 个 warning）') ($warnLines.Count -eq 0) '0' $warnLines.Count.ToString()
    if ($infoLines.Count -gt 0) {
      Warn 'F2c' ('flutter analyze 有 ' + $infoLines.Count + ' 个 info 级 lint（非 error/warning，不阻塞验收）') (($infoLines | Select-Object -First 3) -join ' | ')
      Info ('analyze 汇总行：' + $issuesLine + '；新平台严选文件命中 lint 数=' + @($infoLines | Where-Object { $_ -match 'platform_' }).Count)
    }
    $script:flutterInfo['analyze'] = [ordered]@{ exit=$anRes.exit; seconds=$anRes.seconds; errorCount=$errLines.Count; warningCount=$warnLines.Count; infoCount=$infoLines.Count; summary=$issuesLine; log=(Join-Path $logDirAbs 'flutter-analyze.out.log') }
  }
  # F3 test
  $teRes = Invoke-NativeLogged $script:Flutter 'test' $appDir (Join-Path $logDirAbs 'flutter-test') $TestTimeoutSec
  $teLog = Get-NativeLog (Join-Path $logDirAbs 'flutter-test')
  if ($teRes.timedOut) {
    Check 'F3' ('flutter test 完成（' + $TestTimeoutSec + 's 内）') $false '未超时' ($TestTimeoutSec.ToString() + 's 超时')
  } else {
    $passed = -1
    $m = [regex]::Matches($teLog, '\+(\d+)(?:\s|:)')
    if ($m.Count -gt 0) { $passed = [int]$m[$m.Count - 1].Groups[1].Value }
    $allPassed = $teLog.Contains('All tests passed!')
    Check 'F3' ('flutter test exit=0（耗时 ' + $teRes.seconds + 's）') ($teRes.exit -eq 0) 'exit=0' ('exit=' + $teRes.exit + ' tail=' + (Get-LogTail $teLog 10))
    Check 'F4' ('flutter test 全部通过（通过数 ' + $passed + '，"All tests passed!"=' + $allPassed + '）') ($teRes.exit -eq 0 -and ($allPassed -or $passed -gt 0)) 'All tests passed!' ('passed=' + $passed)
    $script:flutterInfo['test'] = [ordered]@{ exit=$teRes.exit; seconds=$teRes.seconds; passed=$passed; allPassed=$allPassed; log=(Join-Path $logDirAbs 'flutter-test.out.log') }
  }
  # F5 build apk
  $buildStart = Get-Date
  $bdRes = Invoke-NativeLogged $script:Flutter 'build apk --debug' $appDir (Join-Path $logDirAbs 'flutter-build-apk') $BuildTimeoutSec
  $bdLog = Get-NativeLog (Join-Path $logDirAbs 'flutter-build-apk')
  $apkPath = Join-Path $appDir 'build\app\outputs\flutter-apk\app-debug.apk'
  $apkOk = (Test-Path -LiteralPath $apkPath)
  $apkSize = 0; $apkTime = ''
  if ($apkOk) { $apkItem = Get-Item -LiteralPath $apkPath; $apkSize = [math]::Round($apkItem.Length / 1MB, 1); $apkTime = $apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') }
  Check 'F5' ('flutter build apk --debug 成功（耗时 ' + $bdRes.seconds + 's）') ($bdRes.exit -eq 0 -and -not $bdRes.timedOut) 'exit=0' ('exit=' + $bdRes.exit + ' timedOut=' + $bdRes.timedOut + ' tail=' + (Get-LogTail $bdLog 8))
  Check 'F6' ('APK 产物存在并已刷新（' + $apkSize + ' MB，构建于 ' + $apkTime + '）') ($apkOk -and ($apkItem.LastWriteTime -ge $buildStart.AddMinutes(-1))) 'APK 存在且为本次构建' $apkPath
  $script:flutterInfo['apk'] = [ordered]@{ path=$apkPath; exists=$apkOk; sizeMB=$apkSize; builtAt=$apkTime; seconds=$bdRes.seconds; log=(Join-Path $logDirAbs 'flutter-build-apk.out.log') }
}

# ================================================================ M5 真机截图（尽力而为）
$script:deviceInfo = [ordered]@{}
$script:screenshots = @()
function Invoke-Adb([string[]]$adbArgs) {
  $out = & $script:Adb @adbArgs 2>&1
  $code = $LASTEXITCODE
  return [pscustomobject]@{ exit = $code; text = (($out | ForEach-Object { [string]$_ }) -join "`n") }
}
function Get-DeviceScreenshot([string]$name) {
  $remote = '/sdcard/qa-' + $name + '.png'
  $null = Invoke-Adb @('shell', ('screencap -p ' + $remote))
  $local = Join-Path $shotDirAbs ($name + '.png')
  $pull = Invoke-Adb @('pull', $remote, $local)
  if ((Test-Path -LiteralPath $local) -and ((Get-Item -LiteralPath $local).Length -gt 1000)) {
    $script:screenshots += $local
    return $local
  }
  return ''
}
function Get-UiDump([string]$name) {
  $remote = '/sdcard/qa-ui-' + $name + '.xml'
  $ok = $false
  for ($i = 1; $i -le 3; $i++) {
    $r = Invoke-Adb @('shell', ('uiautomator dump ' + $remote))
    if ($r.text.Contains('dumped to')) { $ok = $true; break }
    Start-Sleep -Milliseconds 800
  }
  $local = Join-Path $shotDirAbs ('ui-' + $name + '.xml')
  if ($ok) {
    $null = Invoke-Adb @('pull', $remote, $local)
  }
  if (-not (Test-Path -LiteralPath $local)) { return '' }
  return $local
}
function Get-UiAttr($node, [string]$name) {
  if ($null -eq $node) { return '' }
  try {
    if ($node -is [System.Xml.XmlElement]) { return [string]$node.GetAttribute($name) }
    return [string]$node.$name
  } catch { return '' }
}
function Get-UiTexts([string]$xmlPath) {
  if (-not $xmlPath -or -not (Test-Path -LiteralPath $xmlPath)) { return @() }
  try { [xml]$doc = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8 } catch { return @() }
  $texts = @()
  foreach ($n in $doc.SelectNodes('//node')) {
    $t = Get-UiAttr $n 'text'
    if ($t) { $texts += $t }
    $c = Get-UiAttr $n 'content-desc'
    if ($c) { $texts += $c }
  }
  return $texts
}
function Find-UiText([string]$xmlPath, [string]$needle) {
  if (-not $xmlPath -or -not (Test-Path -LiteralPath $xmlPath)) { return $null }
  try { [xml]$doc = Get-Content -LiteralPath $xmlPath -Raw -Encoding UTF8 } catch { return $null }
  foreach ($n in $doc.SelectNodes('//node')) {
    # 入口可能是 Text（text 属性）也可能是带无障碍标签的 View（content-desc 属性）——两种都要认
    $t = Get-UiAttr $n 'text'
    $c = Get-UiAttr $n 'content-desc'
    $label = if ($t) { $t } else { $c }
    if (-not $label -or -not $label.Contains($needle)) { continue }
    $b = Get-UiAttr $n 'bounds'
    if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
      $x1 = [int]$matches[1]; $y1 = [int]$matches[2]; $x2 = [int]$matches[3]; $y2 = [int]$matches[4]
      return [pscustomobject]@{ text=$label; x=[int](($x1 + $x2) / 2); y=[int](($y1 + $y2) / 2); bounds=$b; source=$(if ($t) { 'text' } else { 'content-desc' }) }
    }
  }
  return $null
}
function Tap-UiNode($node) {
  if ($null -eq $node) { return $false }
  $r = Invoke-Adb @('shell', ('input tap ' + $node.x + ' ' + $node.y))
  Start-Sleep -Milliseconds 500
  return ($r.exit -eq 0)
}
function Wait-UiText([string]$dumpName, [string]$needle, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    $xml = Get-UiDump $dumpName
    if ($xml) {
      $node = Find-UiText $xml $needle
      if ($null -ne $node) { return [pscustomobject]@{ xml=$xml; node=$node } }
    }
    Start-Sleep -Seconds 2
  }
  return $null
}
function Get-ScreenSize {
  # 真机分辨率不能写死：KOZ AL00 实测 720x1600（上一版写 540/1800 坐标直接滑不动）
  $w = 720; $h = 1600
  try {
    $r = Invoke-Adb @('shell', 'wm size')
    $m = [regex]::Match($r.text, '(\d+)x(\d+)')
    if ($m.Success) { $w = [int]$m.Groups[1].Value; $h = [int]$m.Groups[2].Value }
  } catch { }
  return [pscustomobject]@{ w = $w; h = $h }
}
function Swipe-Up {
  $s = $script:screen
  if ($null -eq $s) { $s = Get-ScreenSize; $script:screen = $s }
  $x = [int]($s.w / 2)
  $fromY = [int]($s.h * 0.72)
  $toY = [int]($s.h * 0.28)
  $null = Invoke-Adb @('shell', ('input swipe ' + $x + ' ' + $fromY + ' ' + $x + ' ' + $toY + ' 400'))
  Start-Sleep -Milliseconds 1000
}

Head 'M5 真机截图（尽力而为，失败只 WARN 不阻塞）'
$apkForDevice = ''
if ($script:flutterInfo.Contains('apk')) { $apkForDevice = [string]$script:flutterInfo['apk'].path }
if ($SkipDevice) {
  Skip 'V1..V9' '真机截图' '传了 -SkipDevice'
} elseif (-not $script:Adb) {
  Warn 'V0' 'adb 不可用，跳过真机截图' '未找到 adb（可用 -AdbExe 指定）'
} else {
  # V1 设备可见
  $devices = Invoke-Adb @('devices')
  $deviceVisible = $devices.text -match ([regex]::Escape($Phone) + '\s+device')
  Check 'V1' ('真机已连接（' + $Phone + '）') $deviceVisible 'devices 里出现该序列号' (($devices.text -split "`r?`n") -join ' / ')
  if ($deviceVisible) {
    try {
    # V2 adb reverse 隧道：手机 127.0.0.1:8080 -> 宿主 QA 端口 $Port（不 reverse 到宿主 8080 用户实例！）
    $null = Invoke-Adb @('reverse', '--remove', 'tcp:8080')
    $null = Invoke-Adb @('reverse', '--remove', 'tcp:9010')
    $null = Invoke-Adb @('reverse', 'tcp:8080', ('tcp:' + $Port))
    $null = Invoke-Adb @('reverse', 'tcp:9010', 'tcp:9010')
    $revList = (Invoke-Adb @('reverse', '--list')).text
    $revOk = ($revList -match 'tcp:8080') -and ($revList -match 'tcp:9010')
    Check 'V2' ('adb reverse 已建立：手机 8080 -> 宿主 ' + $Port + '，且 9010 -> 9010') $revOk ('tcp:8080 -> tcp:' + $Port + ' 与 tcp:9010') (($revList -split "`r?`n") -join ' / ')
    Info ('adb reverse --list：' + (($revList -split "`r?`n") -join ' / '))

    # V3 备份 App SharedPreferences（App 内服务器地址保持 127.0.0.1:8080，由 adb reverse 转发到 QA 的 18080）
    $prefsPath = '/data/data/' + $Pkg + '/shared_prefs/FlutterSharedPreferences.xml'
    $prefsBackup = Join-Path $backupDir ('FlutterSharedPreferences.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.xml')
    $null = Invoke-Adb @('shell', ('am force-stop ' + $Pkg))
    Start-Sleep -Milliseconds 800
    $bk = Invoke-Adb @('exec-out', 'run-as', $Pkg, 'cat', $prefsPath)
    $src = $bk.text
    $prefsOk = $src.Length -gt 200 -and $src.Contains('flutter.ershou')
    if ($prefsOk) { [System.IO.File]::WriteAllText($prefsBackup, $src, (New-Object System.Text.UTF8Encoding($false))) }
    Check 'V3' 'App SharedPreferences 已备份（run-as 可读）' $prefsOk '>200 字节且含 flutter.ershou' $(if ($prefsOk) { $prefsBackup } else { '读取失败/过短：' + (Get-LogTail $src 3) })
    $prefsWrote = $false
    if ($prefsOk) {
      $newJson = '{&quot;host&quot;:&quot;127.0.0.1&quot;,&quot;port&quot;:8080,&quot;label&quot;:&quot;QA USB 隧道&quot;,&quot;savedAt&quot;:&quot;' + (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss') + '&quot;}'
      $newSrc = [regex]::Replace($src, '(<string name="flutter\.ershou\.server\.config">)[^<]*(</string>)', ('$1' + $newJson + '$2'))
      if ($newSrc -ne $src) {
        $tmp = Join-Path $backupDir 'qa-prefs-new.xml'
        [System.IO.File]::WriteAllText($tmp, $newSrc, (New-Object System.Text.UTF8Encoding($false)))
        $null = Invoke-Adb @('push', $tmp, '/sdcard/qa-prefs-new.xml')
        # 用管道写入（run-as 以 App uid 覆盖自己的 prefs；实测可行）
        $wr = Invoke-Adb @('shell', ('cat /sdcard/qa-prefs-new.xml | run-as ' + $Pkg + ' sh -c "cat > ' + $prefsPath + '"'))
        Start-Sleep -Milliseconds 600
        $checkSrc = (Invoke-Adb @('exec-out', 'run-as', $Pkg, 'cat', $prefsPath)).text
        $prefsWrote = $checkSrc.Contains('127.0.0.1')
        Check 'V4' 'App 后端地址为 127.0.0.1:8080（USB 隧道 -> QA 18080）' $prefsWrote 'prefs 内含 127.0.0.1' $(if ($prefsWrote) { '已写入并读回' } else { '写后读回失败：' + $wr.text })
      } else {
        $prefsWrote = $src.Contains('127.0.0.1')
        if ($prefsWrote) { Check 'V4' 'App 后端地址本来就是 127.0.0.1:8080（无需改写）' $true '含 127.0.0.1' '已是 USB 隧道地址' }
        else { Warn 'V4' 'App 后端地址改写未生效（prefs 未匹配到 server.config）' '可能为首次安装；将尝试直接启动，看是否进连接页' }
      }
    }

    # V5 安装 APK（-r 保留数据与登录态）
    if (-not $apkForDevice -or -not (Test-Path -LiteralPath $apkForDevice)) {
      Warn 'V5' 'APK 不可用，跳过安装' $(if ($apkForDevice) { '不存在：' + $apkForDevice } else { '未构建 APK（-SkipFlutter 时需先构建）' })
    } else {
      $inst = Invoke-Adb @('install', '-r', $apkForDevice)
      Check 'V5' ('adb install -r 新 APK 成功（' + [math]::Round((Get-Item -LiteralPath $apkForDevice).Length / 1MB, 1) + ' MB）') ($inst.text -match 'Success') 'Success' (($inst.text -split "`r?`n" | Select-Object -Last 2) -join ' / ')
      if ($RestoreDevicePrefs -and $prefsOk) {
        # 可选：装完把 prefs 恢复成跑之前那份（-RestoreDevicePrefs）
        $null = Invoke-Adb @('push', $prefsBackup, '/sdcard/qa-prefs-restore.xml')
        $null = Invoke-Adb @('shell', ('cat /sdcard/qa-prefs-restore.xml | run-as ' + $Pkg + ' sh -c "cat > ' + $prefsPath + '"'))
        Info '-RestoreDevicePrefs：已恢复真机原 SharedPreferences'
      }
    }

    # V6 启动 App（拼字符串必须整体括起来，否则 PowerShell 会把 '+' 当成独立参数）
    $null = Invoke-Adb @('shell', ('am force-stop ' + $Pkg))
    Start-Sleep -Milliseconds 800
    $start = Invoke-Adb @('shell', ('am start -W -n ' + $Activity))
    Start-Sleep -Seconds 9
    $v6ok = [bool](($start.exit -eq 0) -and (-not ($start.text -match 'Error')))
    Check 'V6' ('启动 App（am start -W ' + $Activity + '）') $v6ok '启动无 Error' (($start.text -split "`r?`n") -join ' / ')

    # V7 首页截图 + 「严选」入口（入口是带 content-desc 的 View，不是 Text）
    $homePng = Get-DeviceScreenshot '01-home'
    Check 'V7' '首页截图已保存' ($homePng.Length -gt 0) 'PNG > 1KB' $homePng
    $homeXml = Get-UiDump 'home'
    $homeTexts = Get-UiTexts $homeXml
    Info ('首页可见文本（前 40）：' + (($homeTexts | Select-Object -First 40) -join ' | '))
    $entryWait = Wait-UiText 'home' '严选' 15
    $entryNode = $null
    if ($null -ne $entryWait) { $entryNode = $entryWait.node }
    if ($null -eq $entryNode) { $entryNode = Find-UiText $homeXml '严选' }
    Check 'V8' '首页找到「严选」入口' ($null -ne $entryNode) 'UI（text 或 content-desc）里有「严选」' (($homeTexts | Where-Object { $_ -like '*严选*' }) -join ' / ')

    # V9 首页角标：13 件被上架个人商品排在前面、平台商品在后面 → 向下滑最多 8 次找「官方代卖」
    $badgeNode = Find-UiText $homeXml '官方代卖'
    if ($null -eq $badgeNode) { $badgeNode = Find-UiText $homeXml '官方严选' }
    $badgeTexts = $homeTexts
    $swipeCount = 0
    while ($null -eq $badgeNode -and $swipeCount -lt 8) {
      Swipe-Up
      $swipeCount++
      $badgeXml = Get-UiDump ('home-s' + $swipeCount)
      if (-not $badgeXml) { continue }
      $badgeTexts = Get-UiTexts $badgeXml
      $badgeNode = Find-UiText $badgeXml '官方代卖'
      if ($null -eq $badgeNode) { $badgeNode = Find-UiText $badgeXml '官方严选' }
    }
    if ($null -ne $badgeNode) {
      Check 'V9' ('首页商品卡出现「官方代卖」/「官方严选」角标（向下滑 ' + $swipeCount + ' 次后命中）') $true 'UI 含 官方代卖/官方严选' $badgeNode.text
      $null = Get-DeviceScreenshot '01b-home-badge'
    } else {
      Warn 'V9' '首页未在 UI 文本中找到「官方代卖/官方严选」角标' ('已下滑 ' + $swipeCount + ' 次；首页可见文本：' + (($badgeTexts | Select-Object -First 25) -join ' | '))
      $null = Get-DeviceScreenshot '01b-home-scrolled'
    }

    # 先滚回顶部，再点「严选」入口（避免入口已随列表滚出屏幕）
    $s0 = Get-ScreenSize
    $script:screen = $s0
    for ($k = 1; $k -le 4; $k++) {
      $null = Invoke-Adb @('shell', ('input swipe ' + [int]($s0.w / 2) + ' ' + [int]($s0.h * 0.28) + ' ' + [int]($s0.w / 2) + ' ' + [int]($s0.h * 0.72) + ' 300'))
      Start-Sleep -Milliseconds 450
    }
    Start-Sleep -Milliseconds 900
    $entryWait2 = Wait-UiText 'home-top' '严选' 10
    if ($null -ne $entryWait2) { $entryNode = $entryWait2.node }

    # V10 点进平台严选页
    if ($null -ne $entryNode) {
      $null = Tap-UiNode $entryNode
      Start-Sleep -Seconds 3
      $platWait = Wait-UiText 'platform' '平台严选' 20
      if ($null -ne $platWait) {
        Check 'V10' '点击「严选」进入平台严选页（标题「平台严选」可见）' $true 'UI 标题 平台严选' $platWait.node.text
      } else {
        Check 'V10' '点击「严选」进入平台严选页' $false 'UI 标题 平台严选' '20s 内未见「平台严选」（可能仍在首页/连接页；见 ui-platform.xml）'
      }
      $platPng = Get-DeviceScreenshot '02-platform'
      Check 'V11' '平台严选页首屏截图已保存' ($platPng.Length -gt 0) 'PNG > 1KB' $platPng
      # V12 四区块：逐屏滚动收集可见文本（Hero/代卖点/秒杀/券/严选好物/官方代卖角标）
      $allPlatTexts = @()
      $platXml0 = Get-UiDump 'platform'
      $allPlatTexts += Get-UiTexts $platXml0
      for ($i = 1; $i -le 6; $i++) {
        Swipe-Up
        $null = Get-DeviceScreenshot ('0' + (2 + $i) + '-platform-scroll' + $i)
        $px = Get-UiDump ('platform-s' + $i)
        $allPlatTexts += Get-UiTexts $px
      }
      $allJoined = ($allPlatTexts -join ' | ')
      Info ('平台严选页累计可见文本（滚动 6 次）：' + $(if ($allJoined.Length -gt 1600) { $allJoined.Substring(0,1600) + '…' } else { $allJoined }))
      foreach ($sect in @(
          @{ id='V12-0'; name='Hero 标题「校园二手 · 官方严选」'; needle='官方严选' },
          @{ id='V12-1'; name='代卖点区（全部代卖点 chips）'; needle='代卖点' },
          @{ id='V12-2'; name='限时秒杀区'; needle='秒杀' },
          @{ id='V12-3'; name='优惠券区'; needle='优惠券' },
          @{ id='V12-4'; name='严选好物商品列表'; needle='严选好物' },
          @{ id='V12-5'; name='官方代卖角标（平台商品卡）'; needle='官方代卖' })) {
        $hit = $allJoined.Contains($sect.needle)
        if ($hit) { Check $sect.id ('平台严选页可见：' + $sect.name) $true ('含「' + $sect.needle + '」') '找到' }
        else { Warn $sect.id ('平台严选页未见：' + $sect.name) ('滚动 6 次内 UI 文本未含「' + $sect.needle + '」；可能被虚拟列表回收，见截图 02~09') }
      }
      $script:deviceInfo['platformTexts'] = @($allPlatTexts)
    } else {
      Warn 'V10' '无法进入平台严选页（首页未找到「严选」入口）' '已保存首页截图，导航失败不阻塞（按 task-20：实在导航不了至少截首页并说明）'
    }
    $script:deviceInfo['package'] = $Pkg
    $script:deviceInfo['phone'] = $Phone
  } catch {
    Warn 'V-ERR' '真机截图阶段出现异常（不阻塞验收）' $_.Exception.Message
  }
  }
}

# ================================================================ M6 汇总
Head 'M6 汇总'
# 关闭后端（默认；-KeepBackend 保留）
if ($script:backendStarted -and -not $KeepBackend) {
  Info ('结束后端进程 PID=' + $script:backendProc.Id)
  try { Stop-Process -Id $script:backendProc.Id -Force -ErrorAction Stop } catch { Warn 'M6-1' 'kill 后端进程失败' $_.Exception.Message }
  $freed = Wait-PortFree $Port 15
  if ($freed) { Info ([string]$Port + ' 已释放（只 kill 了 QA 自己的实例；用户 8080 IDEA 实例未动）') } else { Warn 'M6-2' ([string]$Port + ' 可能在 15s 内未释放') ('owner=' + ((Get-PortOwner $Port) | ConvertTo-Json -Compress)) }
  $u = Get-PortOwner 8080
  if ($null -ne $u) { Info ('收尾核对：8080 仍被用户 IDEA 实例占用（pid=' + $u.pid + '），符合预期、未动') }
} elseif ($script:backendStarted -and $KeepBackend) {
  Info ('-KeepBackend：QA 后端仍在 ' + $Port + ' 运行 PID=' + $script:backendProc.Id + '（验收完请 kill 该 PID）')
}

$summary = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  mode = $(if ($DryRun) { 'DryRun' } else { 'Full' })
  target = ($base + '  campusId=' + $CampusId)
  note = ('QA 使用独立端口 ' + $Port + '；8080 为用户 IntelliJ IDEA 开发实例，未 kill / 未 reverse 到它')
  pass = $script:pass; fail = $script:fail; skip = $script:skip; warn = $script:warn
  gates = [ordered]@{ t18 = $script:t18Ok; t19 = $script:t19Ok; jarFresh = $script:jarFresh; portFree = $script:portOk; port = $Port }
  backend = $(if ($script:backendInfo) { $script:backendInfo } else { $null })
  db = $(if ($script:dbInfo.Count -gt 0) { $script:dbInfo } else { $null })
  api = $(if ($script:apiInfo.Count -gt 0) { $script:apiInfo } else { $null })
  flutter = $(if ($script:flutterInfo.Count -gt 0) { $script:flutterInfo } else { $null })
  device = $(if ($script:deviceInfo.Count -gt 0) { $script:deviceInfo } else { $null })
  screenshots = @($script:screenshots)
  evidence = @($script:evidence)
  results = @($script:results)
}
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $outFull -Encoding UTF8

Write-Host ''
Write-Host '------------------------------------------------------------' -ForegroundColor White
if ($script:fail -eq 0) {
  Write-Host ('QA 验收全部通过：' + $script:pass + ' 项断言（WARN ' + $script:warn + '，SKIP ' + $script:skip + '）') -ForegroundColor Green
} else {
  Write-Host ('QA 验收：通过 ' + $script:pass + '，失败 ' + $script:fail + '，WARN ' + $script:warn + '，SKIP ' + $script:skip) -ForegroundColor Red
}
Write-Host ('结果 JSON: ' + $outFull) -ForegroundColor DarkGray
Write-Host ('截图目录 : ' + $shotDirAbs) -ForegroundColor DarkGray
Write-Host ('日志目录 : ' + $logDirAbs) -ForegroundColor DarkGray
Write-Host '------------------------------------------------------------' -ForegroundColor White
exit ([Math]::Min($script:fail, 250))
