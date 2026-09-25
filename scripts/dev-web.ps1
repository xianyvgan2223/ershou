<#
.SYNOPSIS
  启动前端开发服务器（Vite）
.DESCRIPTION
  用法： powershell -ExecutionPolicy Bypass -File .\scripts\dev-web.ps1
  首次运行请先： cd ershou-web ; npm install
#>
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$webDir = Join-Path $root 'ershou-web'
Set-Location $webDir

$nodeDir = 'D:\tools\nodejs1'
if (Test-Path $nodeDir) { $env:Path = $nodeDir + ';' + $env:Path }

if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
  Write-Host '未检测到 node_modules，先执行 npm install ...' -ForegroundColor Yellow
  npm install
}

Write-Host ''
Write-Host '前端启动后访问 http://localhost:5173   （/api 会代理到 127.0.0.1:8080）' -ForegroundColor Green
Write-Host '按 Ctrl+C 停止' -ForegroundColor DarkGray
Write-Host ''
npm run dev
