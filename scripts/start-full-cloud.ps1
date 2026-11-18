<#
.SYNOPSIS
  全量微服务版 · 一键启动后端（8 服务 + 网关，不启动旧单体）
.DESCRIPTION
  启动：user(8081) item(8082) order(8083) chat(8084) map(8085) platform(8086)
        admin(8087) file(8089) gateway(8088)
  前置：Docker 中间件（Nacos/RabbitMQ/Seata/Sentinel）与 MySQL/Redis 已自行启动；不会启动 Docker。
  用法：
    powershell -ExecutionPolicy Bypass -File .\scripts\start-full-cloud.ps1
    ... -Build           # 强制重新打包
    ... -Status          # 只看端口/进程
    ... -NoWait          # 启动后立即返回
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$NoWait,
    [switch]$Status
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $root '.build\full-split'
$logDir = Join-Path $stateDir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$stateFile = Join-Path $stateDir 'pids.json'

$services = @(
    @{ key = 'user'; name = 'user-service'; port = 8081; jar = 'ershou-user-service\target\ershou-user-service-1.0.0.jar' },
    @{ key = 'item'; name = 'item-service'; port = 8082; jar = 'ershou-item-service\target\ershou-item-service-1.0.0.jar' },
    @{ key = 'order'; name = 'order-service'; port = 8083; jar = 'ershou-order-service\target\ershou-order-service-1.0.0.jar' },
    @{ key = 'chat'; name = 'chat-service'; port = 8084; jar = 'ershou-chat-service\target\ershou-chat-service-1.0.0.jar' },
    @{ key = 'map'; name = 'map-service'; port = 8085; jar = 'ershou-map-service\target\ershou-map-service-1.0.0.jar' },
    @{ key = 'platform'; name = 'platform-service'; port = 8086; jar = 'ershou-platform-service\target\ershou-platform-service-1.0.0.jar' },
    @{ key = 'admin'; name = 'admin-service'; port = 8087; jar = 'ershou-admin-service\target\ershou-admin-service-1.0.0.jar' },
    @{ key = 'file'; name = 'file-service'; port = 8089; jar = 'ershou-file-service\target\ershou-file-service-1.0.0.jar' },
    @{ key = 'gateway'; name = 'gateway'; port = 8088; jar = 'ershou-cloud-gateway\target\ershou-cloud-gateway-1.0.0.jar' }
)

$jdk = 'C:\Program Files\Java\jdk-21'
if (Test-Path $jdk) { $env:JAVA_HOME = $jdk; $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function Test-Port([int]$port, [int]$timeoutMs = 400) {
    $c = New-Object System.Net.Sockets.TcpClient
    try {
        $t = $c.ConnectAsync('127.0.0.1', $port)
        return ($t.Wait($timeoutMs) -and $c.Connected)
    } catch { return $false } finally { $c.Close() }
}

if ($Status) {
    Write-Host ''
    Write-Host '全量微服务状态' -ForegroundColor Cyan
    foreach ($s in $services) {
        $open = Test-Port $s.port
        Write-Host ("  {0,-16} :{1,-5} {2}" -f $s.name, $s.port, $(if ($open) { '运行中' } else { '未启动' })) -ForegroundColor $(if ($open) { 'Green' } else { 'DarkGray' })
    }
    $nginxOpen = Test-Port 8090
    Write-Host ("  {0,-16} :{1,-5} {2}" -f 'nginx(L1)', 8090, $(if ($nginxOpen) { '运行中（Docker）' } else { '未启动（cloud-up.ps1 启动）' })) -ForegroundColor $(if ($nginxOpen) { 'Green' } else { 'DarkGray' })
    exit 0
}

# 载入根目录 .env：nacos-configs/*.yaml 里的 ${MYSQL_PASSWORD} 等占位符由服务进程环境解析
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) {
    Write-Host '  [X] 缺少 .env：请先 copy .env.example .env 并填写密码' -ForegroundColor Red
    exit 1
}
[System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) |
    Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } |
    ForEach-Object {
        $line = ($_.TrimStart([char]0xFEFF) -split '#', 2)[0].Trim()
        $k, $v = $line -split '=', 2
        if ($k -and $v) { Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim() }
    }

Write-Host ''
Write-Host '==============================' -ForegroundColor Cyan
Write-Host ' 全量微服务后端启动' -ForegroundColor Cyan
Write-Host '==============================' -ForegroundColor Cyan

# 依赖端口（只检查，不启动 Docker）
$bad = @()
foreach ($p in 8848, 3306, 6380, 5672, 8091) { if (-not (Test-Port $p)) { $bad += $p } }
if ($bad.Count -gt 0) {
    Write-Host ('  [X] 依赖端口未监听: ' + ($bad -join ', ')) -ForegroundColor Red
    Write-Host '      请先启动 Docker 中间件与 MySQL/Redis（脚本不会启动 Docker）' -ForegroundColor Yellow
    exit 1
}
Write-Host '  [OK] Nacos/MySQL/Redis/RabbitMQ/Seata 端口正常' -ForegroundColor Green

# 发布 Nacos 配置（全部 yaml）
$publish = Join-Path $PSScriptRoot 'cloud-publish-config.ps1'
if (Test-Path $publish) {
    Write-Host '  发布 Nacos 配置...' -ForegroundColor Gray
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $publish 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { Write-Host ($out | Select-Object -Last 20) -ForegroundColor Red; exit 1 }
    Write-Host '  [OK] Nacos 配置已发布' -ForegroundColor Green
}

# 打包：缺 jar 或 -Build
$needBuild = $Build
if (-not $needBuild) {
    foreach ($s in $services) { if (-not (Test-Path (Join-Path $root ('ershou-cloud\' + $s.jar)))) { $needBuild = $true; break } }
}
if ($needBuild) {
    Write-Host '  打包 ershou-cloud（首次/强制）...' -ForegroundColor Cyan
    & $mvn -f (Join-Path $root 'ershou-cloud\pom.xml') -DskipTests package -q
    if ($LASTEXITCODE -ne 0) { Write-Host '  [X] 打包失败' -ForegroundColor Red; exit 1 }
    Write-Host '  [OK] 打包完成' -ForegroundColor Green
}

# 读状态
$state = @{}
if (Test-Path $stateFile) {
    try { (Get-Content $stateFile -Raw -Encoding UTF8 | ConvertFrom-Json) | ForEach-Object { $state[$_.key] = $_ } } catch { }
}

$started = @()
foreach ($s in $services) {
    $jar = Join-Path $root ('ershou-cloud\' + $s.jar)
    if (Test-Port $s.port) { Write-Host ("  - {0} :{1} 已监听，跳过" -f $s.name, $s.port) -ForegroundColor DarkGreen; continue }
    if (-not (Test-Path $jar)) { Write-Host ("  [X] {0} 缺 jar: {1}" -f $s.name, $jar) -ForegroundColor Red; continue }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $log = Join-Path $logDir ($s.key + '-' + $stamp + '.log')
    $err = Join-Path $logDir ($s.key + '-' + $stamp + '.err.log')
    $p = Start-Process -FilePath $java -ArgumentList @('-jar', $jar, '--spring.cloud.nacos.discovery.ip=127.0.0.1') -WorkingDirectory (Join-Path $root 'ershou-cloud') `
        -RedirectStandardOutput $log -RedirectStandardError $err -PassThru -WindowStyle Hidden
    $s.pid = $p.Id; $s.log = $log
    $state[$s.key] = [pscustomobject]@{ key = $s.key; name = $s.name; port = $s.port; jar = $jar; procId = $p.Id; log = $log }
    $started += $s
    Write-Host ("  + {0} :{1} 已启动 PID={2}" -f $s.name, $s.port, $p.Id) -ForegroundColor Green
}
$state.Values | ConvertTo-Json -Depth 5 | Set-Content $stateFile -Encoding UTF8

if ($NoWait) { Write-Host '  -NoWait：已返回' -ForegroundColor Yellow; exit 0 }

Write-Host ''
Write-Host '  等待服务端口就绪...' -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    $pending = @($services | Where-Object { -not (Test-Port $_.port) })
    if ($pending.Count -eq 0) { break }
    Start-Sleep -Seconds 2
}
Write-Host ''
$fail = 0
foreach ($s in $services) {
    $open = Test-Port $s.port
    if (-not $open) { $fail++ }
    Write-Host ("  {0,-16} :{1,-5} {2}" -f $s.name, $s.port, $(if ($open) { '就绪' } else { '未就绪' })) -ForegroundColor $(if ($open) { 'Green' } else { 'Red' })
}
Write-Host ''
Write-Host '  网关入口: http://127.0.0.1:8088' -ForegroundColor Green
Write-Host ('  日志: ' + $logDir) -ForegroundColor DarkGray
if ($fail -gt 0) { exit 1 } else { exit 0 }
