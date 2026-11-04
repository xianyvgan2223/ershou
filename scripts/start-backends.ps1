<#
.SYNOPSIS
  校园二手交易平台 · 一键启动后端（旧单体 + Spring Cloud 微服务，只启 Java 进程）

.DESCRIPTION
  本脚本只负责“启动 Java 后端进程”，**不会启动/停止任何 Docker 容器**：
    - 旧单体 ershou-server          : 8080
    - user-service                  : 8081
    - item-service                  : 8082
    - order-service                 : 8083
    - Spring Cloud Gateway          : 8088（微服务演示统一入口）

  前置（需你自己先启动）：
    - MySQL 3306、Redis 6380（本地服务/已有容器）
    - 微服务模式还需要 Docker 中间件：Nacos 8848、RabbitMQ 5672、Seata 8091
    - Java 21（默认 C:\Program Files\Java\jdk-21）

  特性：
    - 幂等：端口已在监听 / 进程还活着 → 跳过，不重复启动，绝不 kill 已有进程；
    - 自动发布 Nacos 配置（微服务模式，调 scripts/cloud-publish-config.ps1，可用 -SkipNacosConfig 关闭）；
    - jar 缺失时自动打包（有 -Build 则强制重新打包）；
    - 日志写到 .build\backend\logs\，PID 记录到 .build\backend\pids.json（供 stop-backends.ps1 使用）；
    - 等待各服务就绪（单体 HTTP、微服务端口 + Nacos 注册、网关 health）；
    - 失败时自动打印对应日志尾部，方便排查。

.PARAMETER Mode
  cloud（默认）：只启动 4 个微服务（8081/8082/8083/8088）
  monolith     ：只启动旧单体（8080）
  all          ：旧单体 + 4 个微服务（全套）

.PARAMETER Build
  强制重新打包（旧单体 + 微服务，视 Mode 而定）；不加时仅在 jar 不存在时自动打包。

.PARAMETER NoWait
  启动进程后立即返回，不等待就绪。

.PARAMETER SkipNacosConfig
  不自动发布 Nacos 配置（默认会发布 4 个 dataId，幂等）。

.PARAMETER SkipChecks
  跳过依赖端口检查（MySQL/Redis/Nacos 等），强制尝试启动。

.PARAMETER Status
  只查看当前后端状态，不启动任何进程。

.PARAMETER TimeoutSec
  等待就绪的超时秒数（默认 180）。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\start-backends.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\start-backends.ps1            # 默认：只启动 4 个微服务
  powershell -ExecutionPolicy Bypass -File .\scripts\start-backends.ps1 -Mode all # 微服务 + 旧单体
  powershell -ExecutionPolicy Bypass -File .\scripts\start-backends.ps1 -Build
  powershell -ExecutionPolicy Bypass -File .\scripts\start-backends.ps1 -Status

.NOTES
  停止：.\scripts\stop-backends.ps1（只停本脚本启动的进程，不动 IDEA/手动启动的实例）。
#>
[CmdletBinding()]
param(
  [ValidateSet('all', 'monolith', 'cloud')]
  [string]$Mode = 'cloud',
  [switch]$Build,
  [switch]$NoWait,
  [switch]$SkipNacosConfig,
  [switch]$SkipChecks,
  [switch]$Status,
  [int]$TimeoutSec = 180
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $root '.build\backend'
$logDir = Join-Path $stateDir 'logs'
$stateFile = Join-Path $stateDir 'pids.json'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# ---------- 基础工具 ----------
function Write-Step([string]$text) { Write-Host ('  ' + $text) -ForegroundColor Gray }

function Test-PortOpen([int]$port, [int]$timeoutMs = 500) {
  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $task = $client.ConnectAsync('127.0.0.1', $port)
    if ($task.Wait($timeoutMs) -and $client.Connected) { return $true }
    return $false
  } catch { return $false } finally { $client.Close() }
}

function Wait-PortOpen([int]$port, [int]$timeoutSec) {
  $deadline = (Get-Date).AddSeconds($timeoutSec)
  while ((Get-Date) -lt $deadline) {
    if (Test-PortOpen $port) { return $true }
    Start-Sleep -Milliseconds 800
  }
  return $false
}

function Get-JavaProc([object]$procId, [string]$jarName) {
  if (-not $procId) { return $null }
  try {
    $p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + [int]$procId) -ErrorAction SilentlyContinue
    if (-not $p) { return $null }
    if ($jarName) {
      $needle = Split-Path $jarName -Leaf
      if ($p.CommandLine -notmatch [regex]::Escape($needle)) { return $null }
    }
    return $p
  } catch { return $null }
}

function Get-ListeningPid([int]$port) {
  try {
    $conn = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($conn) { return [int]$conn.OwningProcess }
  } catch { }
  return $null
}

function Read-State {
  if (-not (Test-Path $stateFile)) { return @() }
  try {
    $raw = Get-Content $stateFile -Raw -Encoding UTF8
    if (-not $raw -or $raw.Trim() -eq '') { return @() }
    $obj = $raw | ConvertFrom-Json
    if ($null -eq $obj) { return @() }
    if ($obj -is [System.Array]) { return $obj }
    return @($obj)
  } catch { return @() }
}

function Save-State([object[]]$entries) {
  $list = @()
  foreach ($e in @($entries)) {
    if ($null -eq $e) { continue }
    $list += $e
  }
  $json = ConvertTo-Json -InputObject $list -Depth 6
  if (-not $json) { $json = '[]' }
  [System.IO.File]::WriteAllText($stateFile, $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Write-Tail([string]$file, [int]$lines = 20) {
  if (Test-Path $file) {
    Write-Host ('    --- ' + (Split-Path $file -Leaf) + '（最后 ' + $lines + ' 行）---') -ForegroundColor DarkYellow
    Get-Content $file -Tail $lines -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ('    ' + $_) -ForegroundColor DarkYellow }
  }
}

function Load-DotEnv {
  $envFile = Join-Path $root '.env'
  if (-not (Test-Path $envFile)) { return }
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | ForEach-Object {
    $line = $_.TrimStart([char]0xFEFF).Trim()
    if (-not $line -or $line.StartsWith('#')) { return }
    $body = ($line -split '#', 2)[0].Trim()
    if ($body -notmatch '^[A-Za-z_][A-Za-z0-9_]*=') { return }
    $kv = $body -split '=', 2
    if ($kv.Count -eq 2 -and $kv[0] -and $kv[1]) {
      Set-Item -Path ('env:' + $kv[0].Trim()) -Value $kv[1].Trim()
    }
  }
}

function Get-NacosInstances([string]$serviceName) {
  try {
    $url = 'http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=' + $serviceName + '&groupName=DEFAULT_GROUP&namespaceId=public'
    $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 4
    $body = $r.Content
    if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
    return ($body -match '"hosts"\s*:\s*\[\s*\{')
  } catch { return $false }
}

# ---------- 环境准备 ----------
Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host '   校园二手交易平台 · 一键启动后端（只启 Java 进程，不动 Docker）' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host ('  模式: ' + $Mode + '   等待超时: ' + $TimeoutSec + 's   时间: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

# Java
$jdk = 'C:\Program Files\Java\jdk-21'
if (Test-Path $jdk) {
  $env:JAVA_HOME = $jdk
  $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) {
  $cmd = Get-Command java -ErrorAction SilentlyContinue
  if ($cmd) { $java = $cmd.Source } else { Write-Host '  [X] 未找到 Java 21（C:\Program Files\Java\jdk-21）' -ForegroundColor Red; exit 1 }
}
Write-Step ('Java: ' + $java)

# Maven（IDEA 自带）
$mvnCandidates = @(
  'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd',
  'D:\IntelliJ IDEA 2024.3.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
$mvn = $null
foreach ($c in $mvnCandidates) { if (Test-Path $c) { $mvn = $c; break } }
if (-not $mvn) {
  $cmd = Get-Command mvn -ErrorAction SilentlyContinue
  if ($cmd) { $mvn = $cmd.Source }
}
if ($mvn) { Write-Step ('Maven: ' + $mvn) }

Load-DotEnv
Write-Step '已载入 .env（若存在）'

# 服务定义
$svcDefs = @(
  [pscustomobject]@{
    Key = 'monolith'; Name = '旧单体 ershou-server'; Port = 8080
    Jar = (Join-Path $root 'ershou-server\target\ershou-server-1.0.0.jar')
    WorkDir = (Join-Path $root 'ershou-server'); Modes = @('all', 'monolith')
    Ready = 'http'; ReadyUrl = 'http://127.0.0.1:8080/api/v1/map/amap-status'
    NacosName = $null; Pid = $null; Status = ''; Log = ''
  },
  [pscustomobject]@{
    Key = 'user'; Name = 'user-service'; Port = 8081
    Jar = (Join-Path $root 'ershou-cloud\ershou-user-service\target\ershou-user-service-1.0.0.jar')
    WorkDir = (Join-Path $root 'ershou-cloud'); Modes = @('all', 'cloud')
    Ready = 'nacos'; ReadyUrl = $null; NacosName = 'ershou-user-service'
    Pid = $null; Status = ''; Log = ''
  },
  [pscustomobject]@{
    Key = 'item'; Name = 'item-service'; Port = 8082
    Jar = (Join-Path $root 'ershou-cloud\ershou-item-service\target\ershou-item-service-1.0.0.jar')
    WorkDir = (Join-Path $root 'ershou-cloud'); Modes = @('all', 'cloud')
    Ready = 'nacos'; ReadyUrl = $null; NacosName = 'ershou-item-service'
    Pid = $null; Status = ''; Log = ''
  },
  [pscustomobject]@{
    Key = 'order'; Name = 'order-service'; Port = 8083
    Jar = (Join-Path $root 'ershou-cloud\ershou-order-service\target\ershou-order-service-1.0.0.jar')
    WorkDir = (Join-Path $root 'ershou-cloud'); Modes = @('all', 'cloud')
    Ready = 'nacos'; ReadyUrl = $null; NacosName = 'ershou-order-service'
    Pid = $null; Status = ''; Log = ''
  },
  [pscustomobject]@{
    Key = 'gateway'; Name = 'Spring Cloud Gateway'; Port = 8088
    Jar = (Join-Path $root 'ershou-cloud\ershou-cloud-gateway\target\ershou-cloud-gateway-1.0.0.jar')
    WorkDir = (Join-Path $root 'ershou-cloud'); Modes = @('all', 'cloud')
    Ready = 'http'; ReadyUrl = 'http://127.0.0.1:8088/actuator/health'
    NacosName = 'ershou-gateway'; Pid = $null; Status = ''; Log = ''
  }
)

$targets = @($svcDefs | Where-Object { $_.Modes -contains $Mode })

# ---------- Status 模式 ----------
if ($Status) {
  Write-Host ''
  Write-Host '----- 当前后端状态（只看不动）-----' -ForegroundColor Cyan
  $state = @(Read-State)
  foreach ($svc in $svcDefs) {
    $portOpen = Test-PortOpen $svc.Port
    $rec = $state | Where-Object { $_.key -eq $svc.Key } | Select-Object -First 1
    $procAlive = $false
    if ($rec) { $procAlive = ($null -ne (Get-JavaProc $rec.procId $rec.jar)) }
    $stateText = '未启动'
    if ($portOpen) { $stateText = '运行中（端口监听）' } elseif ($procAlive) { $stateText = '进程在但端口未就绪' }
    Write-Host ('  {0,-28} :{1,-5} {2}' -f $svc.Name, $svc.Port, $stateText) -ForegroundColor $(if ($portOpen) { 'Green' } else { 'DarkGray' })
  }
  Write-Host ''
  Write-Host ('  PID 记录: ' + $stateFile) -ForegroundColor DarkGray
  exit 0
}

# ---------- 依赖检查（只检查，不启动） ----------
if (-not $SkipChecks) {
  Write-Host ''
  Write-Host '----- 依赖检查（不会启动 Docker/服务，只做检测）-----' -ForegroundColor Cyan
  $fatal = @()
  if (-not (Test-PortOpen 3306)) { $fatal += 'MySQL 3306 未监听（请启动本机 MySQL 服务）' }
  if (-not (Test-PortOpen 6380)) { $fatal += 'Redis 6380 未监听（请启动现有 ershou-redis 容器）' }
  if ($Mode -ne 'monolith') {
    if (-not (Test-PortOpen 8848)) { $fatal += 'Nacos 8848 未监听（微服务必须；请先启动 Docker 中间件）' }
    if (-not (Test-PortOpen 5672)) { Write-Host '  [!] RabbitMQ 5672 未监听：item/order 的 MQ 功能会异常（可继续启动）' -ForegroundColor Yellow }
    if (-not (Test-PortOpen 8091)) { Write-Host '  [!] Seata TC 8091 未监听：order 的分布式事务会失败（可继续启动）' -ForegroundColor Yellow }
  }
  if (-not (Test-PortOpen 9010)) { Write-Host '  [!] MinIO 9010 未监听：商品图片可能加载失败（可继续启动）' -ForegroundColor Yellow }
  if ($fatal.Count -gt 0) {
    foreach ($msg in $fatal) { Write-Host ('  [X] ' + $msg) -ForegroundColor Red }
    Write-Host '  如确认可忽略，使用 -SkipChecks 强制启动。' -ForegroundColor Yellow
    exit 1
  }
  Write-Host '  [OK] 必要依赖端口正常' -ForegroundColor Green
}

# ---------- Nacos 配置发布（微服务模式） ----------
if ($Mode -ne 'monolith' -and -not $SkipNacosConfig) {
  Write-Host ''
  Write-Host '----- 发布 Nacos 配置（幂等，4 个 dataId）-----' -ForegroundColor Cyan
  $publish = Join-Path $PSScriptRoot 'cloud-publish-config.ps1'
  if (Test-Path $publish) {
    # 用子进程调用，避免 & 调用 .ps1 时 $LASTEXITCODE 不可靠 + 输出污染
    $pubOut = (& powershell -NoProfile -ExecutionPolicy Bypass -File $publish 2>&1 | Out-String)
    $pubCode = $LASTEXITCODE
    if ($pubCode -ne 0) {
      Write-Host '  [X] Nacos 配置发布失败：' -ForegroundColor Red
      ($pubOut -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 15) | ForEach-Object { Write-Host ('    ' + $_) -ForegroundColor DarkYellow }
      Write-Host '  请检查 Nacos 是否正常，或用 -SkipNacosConfig 跳过（配置已存在时可跳过）。' -ForegroundColor Yellow
      exit 1
    }
    $okLine = ($pubOut -split "`r?`n" | Where-Object { $_ -match '发布完成' } | Select-Object -Last 1)
    if ($okLine) { Write-Host ('  [OK] ' + $okLine.Trim()) -ForegroundColor Green }
    else { Write-Host '  [OK] Nacos 配置发布完成（4 个 dataId）' -ForegroundColor Green }
  } else {
    Write-Host '  [!] 未找到 scripts/cloud-publish-config.ps1，跳过配置发布（请确保 Nacos 里已有 4 个配置）' -ForegroundColor Yellow
  }
}

# ---------- 打包（按需） ----------
function Invoke-Build([string]$pomPath, [string]$label) {
  if (-not $mvn) { Write-Host ('  [X] 需要打包 ' + $label + '，但未找到 Maven') -ForegroundColor Red; return $false }
  Write-Host ('  正在打包 ' + $label + ' ...') -ForegroundColor Cyan
  Push-Location (Split-Path $pomPath -Parent)
  try {
    & $mvn -B -DskipTests package -f $pomPath 2>&1 | ForEach-Object { Write-Host ('    ' + $_) -ForegroundColor DarkGray }
    $code = $LASTEXITCODE
  } finally { Pop-Location }
  if ($code -ne 0) {
    Write-Host ('  [X] 打包失败: ' + $label) -ForegroundColor Red
    return $false
  }
  Write-Host ('  [OK] 打包完成: ' + $label) -ForegroundColor Green
  return $true
}

$buildMonolith = ($Mode -ne 'cloud')
$buildCloud = ($Mode -ne 'monolith')
$monolithJar = ($svcDefs | Where-Object { $_.Key -eq 'monolith' }).Jar
$cloudJars = @($svcDefs | Where-Object { $_.Modes -contains 'cloud' } | ForEach-Object { $_.Jar })
$needMonolithBuild = $buildMonolith -and ($Build -or -not (Test-Path $monolithJar))
$needCloudBuild = $buildCloud -and ($Build -or (@($cloudJars | Where-Object { -not (Test-Path $_) }).Count -gt 0))

if ($needMonolithBuild -or $needCloudBuild) {
  Write-Host ''
  Write-Host '----- 打包（首次或缺 jar，可用 -Build 强制）-----' -ForegroundColor Cyan
  if ($needMonolithBuild) {
    if (-not (Invoke-Build (Join-Path $root 'ershou-server\pom.xml') '旧单体 ershou-server')) { exit 1 }
  }
  if ($needCloudBuild) {
    if (-not (Invoke-Build (Join-Path $root 'ershou-cloud\pom.xml') 'Spring Cloud 5 模块')) { exit 1 }
  }
} else {
  Write-Host ''
  Write-Host '----- 跳过打包（所有 jar 已存在；如需重新打包用 -Build）-----' -ForegroundColor DarkGray
}

# ---------- 启动 ----------
Write-Host ''
Write-Host '----- 启动 Java 后端 -----' -ForegroundColor Cyan
$state = @(Read-State)
$stateByKey = @{}
foreach ($e in $state) { if ($e -and $e.key) { $stateByKey[[string]$e.key] = $e } }

$startedNew = @()
foreach ($svc in $targets) {
  # 1) 端口已监听 → 跳过（可能是 IDEA / 之前启动的实例）
  if (Test-PortOpen $svc.Port) {
    $svc.Pid = Get-ListeningPid $svc.Port
    $svc.Status = '已在运行（跳过）'
    Write-Host ('  - ' + $svc.Name + ' :' + $svc.Port + ' 已在监听，跳过') -ForegroundColor DarkGreen
    continue
  }

  # 2) 状态文件里有存活进程 → 跳过
  $rec = $stateByKey[$svc.Key]
  if ($rec -and (Get-JavaProc $rec.procId $rec.jar)) {
    $svc.Pid = [int]$rec.procId
    $svc.Status = '已在运行（跳过）'
    Write-Host ('  - ' + $svc.Name + ' 进程存活 (PID ' + $rec.procId + ')，跳过') -ForegroundColor DarkGreen
    continue
  }

  # 3) jar 检查
  if (-not (Test-Path $svc.Jar)) {
    $svc.Status = '启动失败（jar 不存在）'
    Write-Host ('  [X] ' + $svc.Name + ' 缺少 jar: ' + $svc.Jar) -ForegroundColor Red
    continue
  }

  # 4) 启动
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $log = Join-Path $logDir ($svc.Key + '-' + $stamp + '.log')
  $errLog = Join-Path $logDir ($svc.Key + '-' + $stamp + '.err.log')
  $svc.Log = $log
  try {
    $proc = Start-Process -FilePath $java -ArgumentList @('-jar', $svc.Jar) `
      -WorkingDirectory $svc.WorkDir `
      -RedirectStandardOutput $log -RedirectStandardError $errLog `
      -PassThru -WindowStyle Hidden
    $svc.Pid = $proc.Id
    $svc.Status = '启动中'
    $startedNew += $svc
    $stateByKey[$svc.Key] = [pscustomobject]@{
      key = $svc.Key; name = $svc.Name; port = $svc.Port; jar = $svc.Jar
      workDir = $svc.WorkDir; procId = $proc.Id; log = $log; errLog = $errLog
      startedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    Write-Host ('  + ' + $svc.Name + ' :' + $svc.Port + ' 已启动 (PID ' + $proc.Id + ')') -ForegroundColor Green
  } catch {
    $svc.Status = '启动失败（异常）'
    Write-Host ('  [X] ' + $svc.Name + ' 启动异常: ' + $_.Exception.Message) -ForegroundColor Red
  }
}

# 保存状态（清理已死记录）
$finalState = @()
foreach ($k in $stateByKey.Keys) {
  $e = $stateByKey[$k]
  if ($e -and ($null -ne (Get-JavaProc $e.procId $e.jar))) { $finalState += $e }
}
# 补上“已在运行但不是本脚本启动”的不记录（避免 stop 误杀）；只记录本脚本启动的
Save-State $finalState

if ($NoWait) {
  Write-Host ''
  Write-Host '已按 -NoWait 返回（进程在后台运行，稍后用 -Status 查看就绪情况）' -ForegroundColor Yellow
  Write-Host ('日志目录: ' + $logDir) -ForegroundColor DarkGray
  exit 0
}

# ---------- 等待就绪 ----------
if ($startedNew.Count -gt 0 -or @($targets | Where-Object { $_.Status -eq '启动中' }).Count -gt 0) {
  Write-Host ''
  Write-Host ('----- 等待就绪（最多 ' + $TimeoutSec + ' 秒）-----') -ForegroundColor Cyan
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $pending = @($targets | Where-Object { $_.Status -eq '启动中' })
    if ($pending.Count -eq 0) { break }
    foreach ($svc in $pending) {
      $alive = $null -ne (Get-JavaProc $svc.Pid $svc.Jar)
      if (-not $alive) {
        $svc.Status = '启动失败（进程退出）'
        continue
      }
      $ready = $false
      if ($svc.Ready -eq 'http') {
        try {
          $r = Invoke-WebRequest -Uri $svc.ReadyUrl -UseBasicParsing -TimeoutSec 3
          $body = $r.Content
          if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
          if ($r.StatusCode -eq 200 -and ($svc.Key -ne 'monolith' -or $body -match '"code"\s*:\s*0')) { $ready = $true }
        } catch { $ready = $false }
      } elseif ($svc.Ready -eq 'nacos') {
        if ((Test-PortOpen $svc.Port) -and (Get-NacosInstances $svc.NacosName)) { $ready = $true }
      }
      if ($ready) { $svc.Status = '就绪' }
    }
    Start-Sleep -Milliseconds 900
  }
  foreach ($svc in @($targets | Where-Object { $_.Status -eq '启动中' })) { $svc.Status = '超时（未就绪）' }
}

# ---------- 结果汇总 ----------
Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host '   启动结果' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan
$hasFailure = $false
foreach ($svc in $targets) {
  $color = 'Gray'
  $icon = '·'
  if ($svc.Status -like '就绪') { $color = 'Green'; $icon = 'OK' }
  elseif ($svc.Status -like '已在运行*') { $color = 'DarkGreen'; $icon = 'OK' }
  elseif ($svc.Status -like '启动中') { $color = 'Yellow'; $icon = '..' }
  elseif ($svc.Status -like '*失败*' -or $svc.Status -like '超时*') { $color = 'Red'; $icon = 'XX'; $hasFailure = $true }
  $pidText = ''
  if ($svc.Pid) { $pidText = ' PID=' + $svc.Pid }
  Write-Host ('  [{0}] {1,-26} :{2,-5} {3}{4}' -f $icon, $svc.Name, $svc.Port, $svc.Status, $pidText) -ForegroundColor $color
}

if ($hasFailure) {
  Write-Host ''
  Write-Host '----- 失败服务日志（尾部）-----' -ForegroundColor Red
  foreach ($svc in @($targets | Where-Object { $_.Status -like '*失败*' -or $_.Status -like '超时*' })) {
    if ($svc.Log) { Write-Tail $svc.Log 25 }
    $errGuess = [System.IO.Path]::ChangeExtension($svc.Log, '.err.log')
    if (Test-Path $errGuess) { Write-Tail $errGuess 15 }
  }
}

Write-Host ''
Write-Host '  访问入口:' -ForegroundColor Cyan
if ($Mode -ne 'cloud') { Write-Host '    旧单体        http://127.0.0.1:8080' -ForegroundColor Green }
if ($Mode -ne 'monolith') {
  Write-Host '    Spring Cloud  http://127.0.0.1:8088（网关统一入口）' -ForegroundColor Green
  Write-Host '    Nacos 控制台  http://127.0.0.1:8848/nacos' -ForegroundColor DarkGray
}
Write-Host ('  日志目录: ' + $logDir) -ForegroundColor DarkGray
Write-Host '  停止服务: powershell -ExecutionPolicy Bypass -File .\scripts\stop-backends.ps1' -ForegroundColor DarkGray
Write-Host '  查看状态: powershell -ExecutionPolicy Bypass -File .\scripts\start-backends.ps1 -Status' -ForegroundColor DarkGray
Write-Host ''
if ($hasFailure) { exit 1 } else { exit 0 }
