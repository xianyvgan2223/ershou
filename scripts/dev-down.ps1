<#
.SYNOPSIS
  校园二手交易平台 · 停止本地依赖（保留数据卷与数据）
.DESCRIPTION
  用法： powershell -ExecutionPolicy Bypass -File .\scripts\dev-down.ps1
#>
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host ''
$out = docker compose stop 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Write-Host $out -ForegroundColor Red; exit 1 }
Write-Host ''
docker ps -a --filter name=ershou --format '{{.Names}}  |  {{.Status}}'
Write-Host ''
Write-Host '已停止。数据保留在 docker volume 中，下次用 .\scripts\dev-up.ps1 启动即可。' -ForegroundColor Green
Write-Host ''
