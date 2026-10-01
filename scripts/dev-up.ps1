<#
.SYNOPSIS
  校园二手交易平台 · 一键启动本地依赖（Redis + MinIO + Elasticsearch）
.DESCRIPTION
  容器的 restart 策略是 "no"（手动启动），所以开机后、或每次启动 Docker Desktop 之后，
  需要执行本脚本（或 docker compose up -d）才会拉起 Redis、MinIO 与 Elasticsearch
  （Elasticsearch 首次启动约 30~60 秒，脚本会等到健康检查通过）。
  MySQL 是本机 Windows 服务（服务名 MySQL），不在此脚本范围内。
  用法： powershell -ExecutionPolicy Bypass -File .\scripts\dev-up.ps1
#>
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host ''
Write-Host '===== 校园二手交易平台 · 启动本地依赖 =====' -ForegroundColor Cyan

# 1) 载入 .env 到当前会话
$envFile = Join-Path $root '.env'
if (-not (Test-Path $envFile)) {
  Write-Host '未找到 .env，请先执行： copy .env.example .env  并填写密码' -ForegroundColor Red
  exit 1
}
$vars = [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' }
foreach ($line in $vars) {
  $k, $v = $line -split '=', 2
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
Write-Host ('已载入 .env（' + $vars.Count + ' 个变量）')

# 2) 检查 Docker 引擎
docker info *> $null
if ($LASTEXITCODE -ne 0) {
  Write-Host 'Docker 引擎未运行 —— 请先从开始菜单启动 Docker Desktop，等托盘图标变绿后重跑本脚本' -ForegroundColor Yellow
  exit 1
}

# 3) 启动容器
# 2>&1 合并 stderr：docker compose 的进度信息走 stderr，PS 5.1 会渲染成红色报错，这里统一捕获
$out = docker compose up -d 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Write-Host $out -ForegroundColor Red; Write-Host 'docker compose 启动失败' -ForegroundColor Red; exit 1 }

# 5) 等待 healthy
Write-Host '等待健康检查' -NoNewline
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  $starting = docker ps --filter 'name=ershou' --filter 'health=starting' -q
  if (-not $starting) { break }
  Write-Host '.' -NoNewline
}
Write-Host ''

# 6) 汇总
Write-Host ''
$line = $line.TrimStart([char]0xFEFF).Trim()
# ⚠️ 同一行可能带注释（`# ... KEY=value`），先砍注释再解析
$k, $v = (($line -split '#', 2)[0]) -split '=', 2
if (-not $k -or -not $v) { continue }
Write-Host ''
Write-Host ('Redis  : 127.0.0.1:' + $env:REDIS_PORT + '   (密码见 .env 的 REDIS_PASSWORD)') -ForegroundColor Green
Write-Host ('MinIO  : API http://127.0.0.1:' + $env:MINIO_API_PORT + '   控制台 http://127.0.0.1:' + $env:MINIO_CONSOLE_PORT) -ForegroundColor Green
Write-Host ('ES     : http://127.0.0.1:' + $env:ES_PORT + '   (商品搜索，索引 ershou_item)') -ForegroundColor Green
Write-Host ('MySQL  : 本机服务 127.0.0.1:3306   库 ' + $env:MYSQL_DATABASE + '   (不是容器，需确保服务 MySQL 已启动)') -ForegroundColor Green
Write-Host ''
Write-Host '停止依赖： .\scripts\dev-down.ps1' -ForegroundColor DarkGray
Write-Host ''
