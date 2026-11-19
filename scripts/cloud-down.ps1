﻿﻿﻿﻿﻿<#
.SYNOPSIS
  第 5 轮 Spring Cloud：停止 Docker 中间件（Nacos / RabbitMQ / Seata / Sentinel），可选删除数据卷。

.DESCRIPTION
  对应契约：智能体团队/第5轮-SpringCloud重构-设计.md §七.1
  行为：
    docker compose -f ershou-cloud/docker/docker-compose.yml down [--volumes]
  说明：
    - 本 compose 只含 4 个中间件容器；Redis(6380)/MySQL(3306)/MinIO(9010)/ES(9211) 是既有容器，不受影响；
    - 默认保留 volume（nacos-data/nacos-logs/rabbitmq-data），下次 up 后 Nacos 配置与 RabbitMQ 数据还在；
    - -RemoveVolumes 才会删卷（会清空 Nacos 配置中心已发布内容，慎用；删后需重跑 cloud-up 发布配置）；
    - -DryRun 只打印命令并做 compose 语法校验。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1 -DryRun
  powershell -ExecutionPolicy Bypass -File .\scripts\cloud-down.ps1 -RemoveVolumes   # 连数据卷一起删

.NOTES
  退出码：0=成功；2=Docker 不可用；1=down 失败。
  脚本保存为 UTF-8 BOM（Windows PowerShell 5.1 读中文必需）。
#>
param(
  [string]$ComposeFile = '',
  [switch]$RemoveVolumes,
  [switch]$DryRun
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
if (-not $ComposeFile) { $ComposeFile = Join-Path $root 'ershou-cloud\docker\docker-compose.yml' }
$envFile = Join-Path $root '.env'
$logDir = Join-Path $root '.build\cloud\logs'
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Force -Path $logDir | Out-Null }
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logDir ('cloud-down-' + $ts + '.log')
$script:lines = New-Object System.Collections.ArrayList
function Log([string]$s) { Write-Host $s; $null = $script:lines.Add($s) }

Log '============================================================'
Log (' 第 5 轮 · Spring Cloud 中间件停止  ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Log (' compose: ' + $ComposeFile)
Log '============================================================'

# Docker 可用性
$dockerOk = $false
try { $null = docker version --format '{{.Server.Version}}' 2>&1; if ($LASTEXITCODE -eq 0) { $dockerOk = $true } } catch { }
if (-not $dockerOk) { Log '[FAIL] Docker 不可用（请先启动 Docker Desktop）'; $script:lines | Set-Content -Path $logFile -Encoding UTF8; exit 2 }
if (-not (Test-Path $ComposeFile)) { Log ('[FAIL] compose 文件不存在：' + $ComposeFile); $script:lines | Set-Content -Path $logFile -Encoding UTF8; exit 1 }

# compose 语法校验
$cfgOut = docker compose --env-file $envFile -f $ComposeFile config -q 2>&1
if ($LASTEXITCODE -ne 0) {
  Log ('[FAIL] compose 语法校验失败：' + (($cfgOut | Out-String).Trim()))
  $script:lines | Set-Content -Path $logFile -Encoding UTF8
  exit 1
}
Log '[ OK ] compose 语法校验通过'

$downCmd = 'compose -f "' + $ComposeFile + '" down'
if ($RemoveVolumes) { $downCmd += ' --volumes' }
if ($DryRun) {
  Log ''
  Log ('[DryRun] 将执行: docker ' + $downCmd)
  Log '         默认保留 nacos-data / nacos-logs / rabbitmq-data 三个数据卷'
  Log '         Redis/MySQL/MinIO/ES 为既有容器，不在本 compose 内，不会被停止'
  $script:lines | Set-Content -Path $logFile -Encoding UTF8
  Log ''
  Log ('DryRun 完成，日志：' + $logFile)
  exit 0
}

Log ''
Log ('正在停止中间件：docker ' + $downCmd)
$out = & docker compose --env-file $envFile -f $ComposeFile down 2>&1
if ($RemoveVolumes) { $out = & docker compose --env-file $envFile -f $ComposeFile down --volumes 2>&1 }
$code = $LASTEXITCODE
($out | ForEach-Object { '  ' + [string]$_ }) | ForEach-Object { Log $_ }
if ($code -ne 0) {
  Log ('[FAIL] docker compose down 失败（exit=' + $code + '）')
  $script:lines | Set-Content -Path $logFile -Encoding UTF8
  exit 1
}

# 收尾核对
Log ''
Log '收尾核对：'
$still = @()
foreach ($n in @('ershou-nacos', 'ershou-rabbitmq', 'ershou-seata', 'ershou-sentinel')) {
  $state = ([string](docker inspect --format '{{.State.Status}}' $n 2>&1)).Trim()
  if ($state -eq 'running') { $still += $n } else { Log ('  ' + $n + ' -> ' + $state) }
}
foreach ($n in @('ershou-redis', 'ershou-minio', 'ershou-es')) {
  $state = ([string](docker inspect --format '{{.State.Status}}' $n 2>&1)).Trim()
  Log ('  ' + $n + '（既有容器，未接管） -> ' + $state)
}
if ($still.Count -gt 0) {
  Log ('  [WARN] 仍有中间件容器在运行：' + ($still -join ', '))
} else {
  Log '  [ OK ] 4 个中间件容器均已停止'
}
Log ('日志：' + $logFile)
$script:lines | Set-Content -Path $logFile -Encoding UTF8
exit 0
