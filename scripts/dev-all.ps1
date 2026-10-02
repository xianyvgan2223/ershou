<#
.SYNOPSIS
  校园二手交易平台 · 一键启动前后端（依赖自检 + 前后端各开一个窗口 + 就绪等待）
.DESCRIPTION
  做四件事：
    1) 自检依赖：MySQL 服务、Redis/MinIO/ES 容器、JDK21、Maven、Node、前端 node_modules；
       容器没起会自动调用 dev-up.ps1 拉起（ES 首启约 30~60 秒）。
    2) 新开一个窗口跑后端（scripts/dev-server.ps1）→ http://127.0.0.1:8080
    3) 新开一个窗口跑前端（scripts/dev-web.ps1）  → http://localhost:5173
    4) 轮询等待两端就绪，最后打印地址；后端日志窗口保留不自动关。

  用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\dev-all.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\dev-all.ps1 -NoWait        # 不等就绪，直接返回
    powershell -ExecutionPolicy Bypass -File .\scripts\dev-all.ps1 -BackendOnly
    powershell -ExecutionPolicy Bypass -File .\scripts\dev-all.ps1 -FrontendOnly
    powershell -ExecutionPolicy Bypass -File .\scripts\dev-all.ps1 -TimeoutSec 300 # 后端首次拉依赖用

  端口已在监听时不会重复启动（幂等），可直接重复执行。
#>
[CmdletBinding()]
param(
  [int]$TimeoutSec = 240,
  [switch]$NoWait,
  [switch]$BackendOnly,
  [switch]$FrontendOnly,
  [switch]$SkipDeps
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$nodeDir = 'D:\tools\nodejs1'

function Write-Step($msg) { Write-Host ('  ' + $msg) }
function Write-Ok($msg)   { Write-Host ('  [OK]   ' + $msg) -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host ('  [警告] ' + $msg) -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host ('  [失败] ' + $msg) -ForegroundColor Red }

function Test-PortListening([int]$Port) {
  $null -ne (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

# 用 Start-Process 开新窗口跑脚本；窗口保留不自动关，方便看日志
function Start-InWindow([string]$Title, [string]$ScriptPath) {
  $args = @('-NoExit', '-ExecutionPolicy', 'Bypass', '-File', $ScriptPath)
  $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $args -WorkingDirectory $root -PassThru
  if ($p) { Write-Ok ($Title + ' 已在新窗口启动（PID ' + $p.Id + '）') } else { Write-Err ($Title + ' 启动失败') }
  return $p
}

Write-Host ''
Write-Host '=========================================================' -ForegroundColor Cyan
Write-Host '  校园二手交易平台 · 一键启动前后端' -ForegroundColor Cyan
Write-Host '=========================================================' -ForegroundColor Cyan

if (-not $SkipDeps) {
  Write-Host ''
  Write-Host '[1/4] 自检本地依赖' -ForegroundColor White

  # MySQL：本机服务，库 ershou 在 3306（注意不是 MySQL80/3308）
  $mysqlSvc = Get-Service -Name 'MySQL' -ErrorAction SilentlyContinue
  if ($null -eq $mysqlSvc) {
    Write-Warn2 '未找到名为 MySQL 的服务；后端首次请求会报 90001'
  } elseif ($mysqlSvc.Status -ne 'Running') {
    Write-Step 'MySQL 服务未运行，正在启动 ...'
    try { Start-Service -Name 'MySQL'; Write-Ok 'MySQL 服务已启动' }
    catch { Write-Err ('MySQL 启动失败：' + $_.Exception.Message + '（需要管理员权限）') }
  } else {
    Write-Ok 'MySQL 服务运行中（127.0.0.1:3306）'
  }

  # Redis / MinIO / ES：docker 容器，restart=no，需要时用 dev-up.ps1 拉起
  $containersUp = $true
  foreach ($name in 'ershou-redis', 'ershou-minio', 'ershou-es') {
    $state = docker inspect -f '{{.State.Status}}' $name 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -ne 'running') { $containersUp = $false; break }
  }
  if ($containersUp) {
    Write-Ok 'Redis(6380) / MinIO(9010,9011) / Elasticsearch(9210) 容器运行中'
  } else {
    Write-Warn2 '有容器未运行，调用 dev-up.ps1 拉起（ES 首启约 30~60 秒）'
    & powershell.exe -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'dev-up.ps1')
  }

  if (-not $FrontendOnly) {
    if (Test-Path 'C:\Program Files\Java\jdk-21') { Write-Ok 'JDK 21 已就绪' } else { Write-Err '未找到 C:\Program Files\Java\jdk-21' }
    $mvnCandidates = @(
      'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd',
      'D:\IntelliJ IDEA 2024.3.3\plugins\maven\lib\maven3\bin\mvn.cmd'
    )
    $mvn = $null
    foreach ($c in $mvnCandidates) { if (Test-Path $c) { $mvn = $c; break } }
    if ($null -eq $mvn) { $cmd = Get-Command mvn -ErrorAction SilentlyContinue; if ($cmd) { $mvn = $cmd.Source } }
    if ($mvn) { Write-Ok ('Maven 已就绪：' + $mvn) } else { Write-Err '未找到 Maven（IDEA 自带 / 全局 mvn 都没有）' }
  }

  if (-not $BackendOnly) {
    $nodeExe = Join-Path $nodeDir 'node.exe'
    if (Test-Path $nodeExe) { Write-Ok ('Node 已就绪：' + (& $nodeExe -v) + '  ' + $nodeDir) }
    elseif (Get-Command node -ErrorAction SilentlyContinue) { Write-Ok ('Node 已就绪：' + (& node -v)) }
    else { Write-Err ('未找到 node（既没有 ' + $nodeDir + '，也不在 PATH）') }

    if (Test-Path (Join-Path $root 'ershou-web\node_modules')) {
      Write-Ok '前端依赖 node_modules 已存在'
    } else {
      Write-Warn2 '缺少 node_modules，前端窗口会先自动 npm install'
    }
  }
}

Write-Host ''
Write-Host '[2/4] 启动后端 / 前端' -ForegroundColor White

# ---- 后端 ----
if (-not $FrontendOnly) {
  if (Test-PortListening 8080) {
    Write-Ok '后端 8080 已在监听，跳过启动'
  } else {
    Start-InWindow '后端' (Join-Path $PSScriptRoot 'dev-server.ps1') | Out-Null
  }
}

# ---- 前端 ----
if (-not $BackendOnly) {
  if (Test-PortListening 5173) {
    Write-Ok '前端 5173 已在监听，跳过启动'
  } else {
    Start-InWindow '前端' (Join-Path $PSScriptRoot 'dev-web.ps1') | Out-Null
  }
}

if ($NoWait) {
  Write-Host ''
  Write-Host '已按 -NoWait 返回，两端在各自窗口里继续启动。' -ForegroundColor DarkGray
  Write-Host ''
  exit 0
}

Write-Host ''
Write-Host ('[3/4] 等待就绪（最多 ' + $TimeoutSec + ' 秒）' + $(if ($FrontendOnly) { '：前端' } elseif ($BackendOnly) { '：后端' } else { '：后端 + 前端' })) -ForegroundColor White

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$backendOk = Test-PortListening 8080
$frontendOk = Test-PortListening 5173
$swaggerOk = $false

while ((Get-Date) -lt $deadline) {
  if (-not $BackendOnly -and -not $frontendOk) {
    # vite 只绑 IPv6 回环 ::1，所以必须用 localhost 探
    try {
      $r = Invoke-WebRequest -Uri 'http://localhost:5173/' -UseBasicParsing -TimeoutSec 3
      if ($r.StatusCode -eq 200) { $frontendOk = $true; Write-Ok '前端已就绪 http://localhost:5173' }
    } catch { }
  }
  if (-not $FrontendOnly -and -not $backendOk) {
    if (Test-PortListening 8080) {
      $backendOk = $true
      Write-Ok '后端端口 8080 已监听'
    }
  }
  if ($backendOk -and -not $swaggerOk -and -not $FrontendOnly) {
    # Spring 起来了接口文档才会通；通了说明 MVC 已初始化完
    try {
      $r2 = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/swagger-ui.html' -UseBasicParsing -MaximumRedirection 3 -TimeoutSec 5
      if ($r2.StatusCode -ge 200 -and $r2.StatusCode -lt 400) { $swaggerOk = $true; Write-Ok '后端接口文档已就绪 /swagger-ui.html' }
    } catch { }
  }
  $needBackend = (-not $FrontendOnly) -and (-not $swaggerOk)
  $needFrontend = (-not $BackendOnly) -and (-not $frontendOk)
  if (-not $needBackend -and -not $needFrontend) { break }
  Write-Host '.' -NoNewline -ForegroundColor DarkGray
  Start-Sleep -Seconds 2
}
Write-Host ''

Write-Host ''
Write-Host '[4/4] 启动结果' -ForegroundColor White
if (-not $FrontendOnly) {
  if ($swaggerOk) { Write-Ok '后端  http://127.0.0.1:8080   接口文档 http://127.0.0.1:8080/swagger-ui.html' }
  elseif ($backendOk) { Write-Warn2 '后端端口已开，但 /swagger-ui.html 还没通（编译中或启动异常，请看后端窗口日志）' }
  else { Write-Err '后端未就绪：请看那个新开的「后端」窗口里的 Maven 报错（首次拉依赖会比较慢，可加大 -TimeoutSec）' }
}
if (-not $BackendOnly) {
  if ($frontendOk) { Write-Ok '前端  http://localhost:5173   （/api 与 /ws 代理到 127.0.0.1:8080）' }
  else { Write-Err '前端未就绪：请看新开的「前端」窗口（node_modules 缺失时会先 npm install）' }
}
Write-Host ''
Write-Host '  提示：必须用 localhost 访问前端，127.0.0.1 连不上（vite 只绑了 IPv6 回环）' -ForegroundColor DarkGray
Write-Host '  停止：关掉前后端那两个窗口；停依赖容器跑 .\scripts\dev-down.ps1' -ForegroundColor DarkGray
Write-Host ''

if ((-not $FrontendOnly -and -not $backendOk) -or (-not $BackendOnly -and -not $frontendOk)) { exit 1 }
exit 0
