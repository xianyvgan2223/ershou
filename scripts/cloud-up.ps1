<#
.SYNOPSIS
  第 5 轮 Spring Cloud：一键启动 Docker 中间件（Nacos / RabbitMQ / Seata / Sentinel）+ 发布 Nacos 配置 + 冒烟验收。

.DESCRIPTION
  对应契约：智能体团队/第5轮-SpringCloud重构-设计.md §七.1（Docker 验收）
  流程：
    0) 检查 Docker 可用
    1) 校验 compose 文件 + sentinel 自建镜像依赖的 jar 是否存在
    2) docker compose up -d（默认 --build，重新构建 sentinel 镜像；-SkipBuild 可跳过）
    3) 等待四容器就绪：nacos/rabbitmq 看 healthcheck；seata/sentinel 无 healthcheck，看 running + 端口
    4) 调 scripts/cloud-publish-config.ps1 把 ershou-cloud/nacos-configs/*.yaml 发布到 Nacos（-SkipPublish 跳过）
    5) 冒烟验收：Nacos readiness / Nacos 服务列表（含 SEATA_GROUP）/ RabbitMQ 管理 API /
       Seata 8091+7091 / Sentinel 8858 / 4 个 dataId 是否可读
    6) 输出各端点、服务启动顺序提示、证据文件路径
  证据：
    .build/cloud/logs/cloud-up-<时间戳>.log   全过程文本日志（含 compose 输出、验收输出）
    .build/cloud/logs/cloud-up-<时间戳>.json  结构化验收结果（每项 pass/fail/实际值）

  前置：Docker Desktop 已启动；本地已有镜像 nacos/nacos-server:v2.3.2、rabbitmq:3-management、
        seataio/seata-server:1.8.0、eclipse-temurin:17-jre；端口 8848/9848/5672/15672/8091/7091/8858 空闲。
  Redis(6380)/MySQL(3306)/MinIO(9010)/ES(9211) 是既有容器，本脚本不接管也不停止。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1 -DryRun          # 只校验计划，不启动
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1 -SkipBuild       # 镜像已是最新时省时间
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-up.ps1 -SkipPublish     # 不发布 Nacos 配置

.NOTES
  退出码：0=全部就绪；>0=失败项数；2=Docker 不可用。
  脚本保存为 UTF-8 BOM（Windows PowerShell 5.1 读中文必需）。
#>
param(
  [string]$ComposeFile = '',
  [string]$PublishScript = '',
  [int]$HealthTimeoutSec = 180,
  [switch]$SkipBuild,
  [switch]$SkipPublish,
  [switch]$SkipVerify,
  [switch]$DryRun
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $ComposeFile) { $ComposeFile = Join-Path $root 'ershou-cloud\docker\docker-compose.yml' }
$envFile = Join-Path $root '.env'
if (-not $PublishScript) { $PublishScript = Join-Path $root 'scripts\cloud-publish-config.ps1' }
$logDir = Join-Path $root '.build\cloud\logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logDir ('cloud-up-' + $ts + '.log')
$jsonFile = Join-Path $logDir ('cloud-up-' + $ts + '.json')

$script:lines = New-Object System.Collections.ArrayList
$script:checks = New-Object System.Collections.ArrayList
$script:fail = 0
function Log([string]$s) {
  Write-Host $s
  $null = $script:lines.Add($s)
}
function Record([string]$id, [string]$item, [bool]$ok, [string]$actual) {
  if (-not $ok) { $script:fail++ }
  $st = if ($ok) { 'PASS' } else { 'FAIL' }
  Write-Host ('  [' + $st + '] ' + $id + ' ' + $item + ' -> ' + $actual) -ForegroundColor $(if ($ok) { 'Green' } else { 'Red' })
  $null = $script:lines.Add('  [' + $st + '] ' + $id + ' ' + $item + ' -> ' + $actual)
  $null = $script:checks.Add([pscustomobject]@{ id = $id; item = $item; status = $st; actual = $actual })
}
function Head([string]$s) { Log ''; Log ('=== ' + $s + ' ===') }

Log '============================================================'
Log (' 第 5 轮 · Spring Cloud 中间件一键启动  ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Log (' compose: ' + $ComposeFile)
Log (' 日志   : ' + $logFile)
Log (' 模式   : ' + $(if ($DryRun) { 'DryRun（只校验，不启动）' } else { '实际启动' }))
Log '============================================================'

# ---------------------------------------------------------------- 0. Docker 可用
Head '0. 环境检查'
$dockerOk = $false
try {
  $ver = docker version --format '{{.Server.Version}}' 2>&1
  if ($LASTEXITCODE -eq 0) { $dockerOk = $true; Log ('  Docker Server ' + ([string]$ver).Trim()) }
} catch { }
if (-not $dockerOk) {
  Log '  [FAIL] Docker 不可用：请先启动 Docker Desktop'
  $script:lines | Set-Content -Path $logFile -Encoding UTF8
  exit 2
}
Record 'E1' 'Docker 可用' $true ('server ' + ([string]$ver).Trim())
if (-not (Test-Path $ComposeFile)) {
  Record 'E2' 'compose 文件存在' $false $ComposeFile
  $script:lines | Set-Content -Path $logFile -Encoding UTF8
  exit 2
}
Record 'E2' 'compose 文件存在' $true $ComposeFile

# compose 语法校验（只解析不启动）
$cfgOut = docker compose --env-file $envFile -f $ComposeFile config -q 2>&1
Record 'E3' 'docker compose config 语法校验' ($LASTEXITCODE -eq 0) (($cfgOut | Out-String).Trim())

# sentinel jar：Dockerfile 里 COPY sentinel-dashboard.jar，缺失则无法 --build
$sentinelJar = Join-Path $root 'ershou-cloud\docker\sentinel\sentinel-dashboard.jar'
$hasSentinelJar = Test-Path $sentinelJar
$hasSentinelImage = $false
$imgOut = docker images --format '{{.Repository}}:{{.Tag}}' 2>&1
if (([string]$imgOut) -match 'ershou/sentinel-dashboard') { $hasSentinelImage = $true }
if (-not $hasSentinelJar) {
  if ($hasSentinelImage -and -not $SkipBuild) {
    Log '  [WARN] sentinel-dashboard.jar 缺失但已有镜像：自动改为 -SkipBuild，避免 --build 失败'
    $SkipBuild = $true
  } elseif (-not $hasSentinelImage) {
    Record 'E4' 'sentinel-dashboard.jar 存在' $false '缺失：需从 https://github.com/alibaba/Sentinel/releases/download/1.8.8/sentinel-dashboard-1.8.8.jar 下载到 ershou-cloud/docker/sentinel/'
  }
} else {
  Record 'E4' 'sentinel-dashboard.jar 存在' $true ([string][math]::Round((Get-Item $sentinelJar).Length / 1MB, 1) + ' MB')
}
Record 'E5' 'sentinel 镜像已构建' $hasSentinelImage $(if ($hasSentinelImage) { 'ershou/sentinel-dashboard:1.8.8' } else { '未构建（本次会 --build）' })

if ($DryRun) {
  Head 'DryRun：计划'
  Log ('  将执行: docker compose -f "' + $ComposeFile + '" up -d' + $(if ($SkipBuild) { '（不 --build）' } else { ' --build' }))
  Log '  将等待: ershou-nacos / ershou-rabbitmq / ershou-nginx healthy；ershou-seata(8091,7091) / ershou-sentinel(8858) running'
  Log ('  将发布: ' + $PublishScript + '（' + $(if ($SkipPublish) { '本次跳过' } else { 'Nacos 配置' }) + '）')
  Log '  将验收: Nacos readiness / Nacos 服务列表 / RabbitMQ API / Seata 端口 / Sentinel 页面 / 4 个 dataId'
  $script:lines | Set-Content -Path $logFile -Encoding UTF8
  Log ''
  Log ('DryRun 完成（失败项 ' + $script:fail + '），日志：' + $logFile)
  if ($script:fail -gt 0) { exit $script:fail }
  exit 0
}

# ---------------------------------------------------------------- 1. 启动 compose
Head '1. docker compose up -d'
$upArgs = 'compose -f "' + $ComposeFile + '" up -d'
if (-not $SkipBuild) { $upArgs += ' --build' }
Log ('  $ docker ' + $upArgs)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
if ($SkipBuild) {
  # 跳过镜像重建（sentinel jar 不变时省时间；镜像必须已存在）
  $upOut = & docker compose --env-file $envFile -f $ComposeFile up -d 2>&1
} else {
  $upOut = & docker compose --env-file $envFile -f $ComposeFile up -d --build 2>&1
}
$upExit = $LASTEXITCODE
$sw.Stop()
($upOut | ForEach-Object { '    ' + [string]$_ }) | ForEach-Object { Log $_ }
$null = $script:lines.Add('')  # 保持日志分节
Record 'U1' ('docker compose up -d 成功（耗时 ' + $sw.Elapsed.TotalSeconds.ToString('0.0') + 's）') ($upExit -eq 0) ('exit=' + $upExit)

# ---------------------------------------------------------------- 2. 等容器就绪
Head '2. 等待五容器就绪'
$want = @(
  @{ name = 'ershou-nacos';        mode = 'health'; ports = @(8848, 9848) },
  @{ name = 'ershou-rabbitmq';     mode = 'health'; ports = @(5672, 15672) },
  @{ name = 'ershou-seata';        mode = 'running'; ports = @(8091, 7091) },
  @{ name = 'ershou-sentinel';     mode = 'running'; ports = @(8858) },
  @{ name = 'ershou-nginx';        mode = 'health'; ports = @(8090) }
)
$deadline = (Get-Date).AddSeconds($HealthTimeoutSec)
$allReady = $false
while ((Get-Date) -lt $deadline) {
  $readyCount = 0
  foreach ($w in $want) {
    $state = ([string](docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $w.name 2>&1)).Trim()
    $parts = $state -split '\|'
    $running = ($parts.Count -ge 1 -and $parts[0] -eq 'running')
    $healthOk = ($parts.Count -ge 2 -and ($parts[1] -eq 'healthy' -or $parts[1] -eq 'none'))
    $portsOk = $true
    foreach ($p in $w.ports) {
      $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($null -eq $c) { $portsOk = $false }
    }
    if ($running -and $healthOk -and $portsOk) { $readyCount++ }
  }
  if ($readyCount -eq $want.Count) { $allReady = $true; break }
  Start-Sleep -Seconds 3
}
foreach ($w in $want) {
  $state = ([string](docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $w.name 2>&1)).Trim()
  $listening = @()
  foreach ($p in $w.ports) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    $listening += ($p.ToString() + '=' + $(if ($null -ne $c) { 'LISTEN' } else { 'NO' }))
  }
  Record ('W-' + $w.name) ($w.name + ' 就绪（状态 ' + $state + '）') ($state -match '^running\|' -and $state -notmatch 'unhealthy') (($listening -join ', '))
}
Record 'W0' ('五容器全部就绪（等待 ' + [int]$HealthTimeoutSec + 's 上限）') $allReady $(if ($allReady) { 'all ready' } else { '存在未就绪项' })

# ---------------------------------------------------------------- 3. 发布 Nacos 配置
Head '3. 发布 Nacos 配置'
if ($SkipPublish) {
  Log '  [SKIP] -SkipPublish'
} elseif (-not (Test-Path $PublishScript)) {
  Record 'P1' 'Nacos 配置发布脚本存在' $false $PublishScript
} else {
  $pubOut = & powershell -NoProfile -ExecutionPolicy Bypass -File $PublishScript 2>&1
  $pubExit = $LASTEXITCODE
  ($pubOut | ForEach-Object { '    ' + [string]$_ }) | ForEach-Object { Log $_ }
  Record 'P1' 'Nacos 配置发布（scripts/cloud-publish-config.ps1）' ($pubExit -eq 0) ('exit=' + $pubExit)
}

# ---------------------------------------------------------------- 4. 冒烟验收
Head '4. 冒烟验收'
$nacos = 'http://127.0.0.1:8848'
function HttpGet([string]$url, [int]$timeout = 8) {
  try {
    $r = Invoke-WebRequest -Uri $url -TimeoutSec $timeout -UseBasicParsing
    return [pscustomobject]@{ ok = $true; status = [int]$r.StatusCode; content = [string]$r.Content }
  } catch {
    return [pscustomobject]@{ ok = $false; status = 0; content = $_.Exception.Message }
  }
}
if (-not $SkipVerify) {
  # N1 Nacos readiness
  $r = HttpGet ($nacos + '/nacos/v1/console/health/readiness')
  Record 'N1' 'Nacos readiness' ($r.ok -and $r.status -eq 200 -and $r.content -match 'OK') ('HTTP ' + $r.status + ' ' + $r.content)
  # N2 服务列表 DEFAULT_GROUP
  $r = HttpGet ($nacos + '/nacos/v1/ns/service/list?pageNo=1&pageSize=50')
  $r2 = HttpGet ($nacos + '/nacos/v1/ns/service/list?pageNo=1&pageSize=50&groupName=SEATA_GROUP')
  Record 'N2' 'Nacos 注册服务列表可查' ($r.ok -and $r.status -eq 200) ('DEFAULT_GROUP=' + $r.content + ' ; SEATA_GROUP=' + $r2.content)
  # N3 SEATA_GROUP 有 seata-server
  Record 'N3' 'Nacos SEATA_GROUP 已注册 seata-server' ($r2.ok -and $r2.content -match 'seata-server') $r2.content
  # N4 四个 dataId 可读
  $cfgOk = 0; $cfgDetail = @()
  foreach ($id in @('ershou-gateway.yaml', 'ershou-user-service.yaml', 'ershou-item-service.yaml', 'ershou-order-service.yaml')) {
    $rr = HttpGet ($nacos + '/nacos/v1/cs/configs?dataId=' + $id + '&group=DEFAULT_GROUP')
    if ($rr.ok -and $rr.status -eq 200 -and $rr.content.Length -gt 50) { $cfgOk++; $cfgDetail += ($id + '=' + $rr.content.Length + 'B') }
    else { $cfgDetail += ($id + '=MISSING') }
  }
  Record 'N4' '4 个 Nacos 配置均已发布可读' ($cfgOk -eq 4) ($cfgDetail -join ', ')
  # N5 RabbitMQ 管理 API
  $pair = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))
  $mqOk = $false; $mqMsg = ''
  try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:15672/api/overview' -Headers @{ Authorization = 'Basic ' + $pair } -TimeoutSec 8 -UseBasicParsing
    $o = $r.Content | ConvertFrom-Json
    $mqOk = ([int]$r.StatusCode -eq 200)
    $mqMsg = ('HTTP ' + [int]$r.StatusCode + ' rabbitmq=' + $o.rabbitmq_version + ' management=' + $o.management_version)
  } catch { $mqMsg = $_.Exception.Message }
  Record 'N5' 'RabbitMQ 管理台/API 可访问' $mqOk $mqMsg
  # N6 Seata 8091/7091
  $s1 = Get-NetTCPConnection -LocalPort 8091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  $s2 = Get-NetTCPConnection -LocalPort 7091 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  $ct = HttpGet 'http://127.0.0.1:7091/'
  Record 'N6' 'Seata TC 8091 监听 + 控制台 7091 可访问' (($null -ne $s1) -and ($null -ne $s2) -and $ct.ok) ('8091=' + $(if ($null -ne $s1) { 'LISTEN' } else { 'NO' }) + ' 7091=' + $(if ($null -ne $s2) { 'LISTEN' } else { 'NO' }) + ' consoleHTTP=' + $ct.status)
  # N7 Sentinel 8858
  $st = HttpGet 'http://127.0.0.1:8858/' 10
  Record 'N7' 'Sentinel Dashboard 8858 返回页面' ($st.ok -and $st.status -eq 200) ('HTTP ' + $st.status + ' bytes=' + $st.content.Length)
  # N8 既有共享容器未被误停（Redis/MinIO/ES 不在本 compose 内，应保持运行）
  $sharedOk = $true; $sharedMsg = @()
  foreach ($n in @('ershou-redis', 'ershou-minio', 'ershou-es')) {
    $state = ([string](docker inspect --format '{{.State.Status}}' $n 2>&1)).Trim()
    $sharedMsg += ($n + '=' + $state)
    if ($state -ne 'running') { $sharedOk = $false }
  }
  Record 'N8' '既有共享容器（Redis/MinIO/ES）保持运行' $sharedOk ($sharedMsg -join ', ')
  # N9 Nginx 健康检查（L1 边缘缓存入口）
  $ng = HttpGet 'http://127.0.0.1:8090/nginx-health' 5
  Record 'N9' 'Nginx 8090 健康检查' ($ng.ok -and $ng.status -eq 200) ('HTTP ' + $ng.status + ' ' + $ng.content)
}

# ---------------------------------------------------------------- 5. 汇总
Head '5. 汇总'
Log '  端点：'
Log '    Nacos            http://127.0.0.1:8848/nacos  （gRPC 9848）'
Log '    RabbitMQ 管理台  http://127.0.0.1:15672       （guest/guest，AMQP 5672）'
Log '    Seata TC         http://127.0.0.1:7091        （事务端口 8091）'
Log '    Sentinel         http://127.0.0.1:8858'
Log '    Nginx 缓存入口   http://127.0.0.1:8090        （L1，仅匿名公开 GET；X-Cache-Status 可观测）'
Log '  服务启动顺序建议：'
Log '    1) 中间件（本脚本）  2) Nacos 配置（本脚本已发布）'
Log '    3) 旧单体 8080（未迁移路径反代目标，可先不启）'
Log '    4) user-service 8081 -> item-service 8082 -> order-service 8083 -> gateway 8088'
Log '    5) 前端/App 指向 gateway 8088（或 Nginx 8090 走三级缓存）'
Log ('  证据：' + $logFile)
Log ('        ' + $jsonFile)

$summary = [ordered]@{
  at = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
  compose = $ComposeFile
  skipBuild = [bool]$SkipBuild
  allReady = $allReady
  pass = (@($script:checks | Where-Object { $_.status -eq 'PASS' }).Count)
  fail = $script:fail
  checks = @($script:checks)
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -Path $jsonFile -Encoding UTF8
$script:lines | Set-Content -Path $logFile -Encoding UTF8

Log ''
if ($script:fail -eq 0) {
  Log '============================================================'
  Log ' 中间件全部就绪，可以启动微服务了'
  Log '============================================================'
  exit 0
} else {
  Log '============================================================'
  Log (' 中间件启动存在失败项：' + $script:fail + '（详见上方 FAIL 行）')
  Log '============================================================'
  exit $script:fail
}
