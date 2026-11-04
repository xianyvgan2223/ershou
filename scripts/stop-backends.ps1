<#
.SYNOPSIS
  校园二手交易平台 · 停止由 start-backends.ps1 启动的后端进程

.DESCRIPTION
  只停止 start-backends.ps1 记录在 .build\backend\pids.json 里的 Java 进程；
  不会动 IDEA / 手动启动的实例，也不会动任何 Docker 容器。
  停止前会核对命令行里是否包含对应 jar，避免 PID 被系统复用时误杀无关进程。

.PARAMETER DryRun
  只列出将要停止的进程，不真正停止。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\stop-backends.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\stop-backends.ps1 -DryRun
#>
[CmdletBinding()]
param(
  [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$stateDir = Join-Path $root '.build\backend'
$stateFile = Join-Path $stateDir 'pids.json'

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

Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host '   校园二手交易平台 · 停止后端（只停本脚本启动的 Java 进程）' -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan

if (-not (Test-Path $stateFile)) {
  Write-Host '  没有找到 PID 记录（.build\backend\pids.json），无需停止。' -ForegroundColor Yellow
  Write-Host '  （IDEA/手动启动的实例不在本脚本管理范围）' -ForegroundColor DarkGray
  exit 0
}

$entries = @()
try {
  $raw = Get-Content $stateFile -Raw -Encoding UTF8
  if ($raw -and $raw.Trim()) {
    $obj = $raw | ConvertFrom-Json
    if ($obj -is [System.Array]) { $entries = $obj } else { $entries = @($obj) }
  }
} catch {
  Write-Host ('  [X] PID 记录解析失败: ' + $_.Exception.Message) -ForegroundColor Red
  exit 1
}

if ($entries.Count -eq 0) {
  Write-Host '  PID 记录为空，无需停止。' -ForegroundColor Yellow
  exit 0
}

$stopped = 0
$kept = 0
foreach ($e in $entries) {
  if (-not $e -or -not $e.procId) { continue }
  $label = if ($e.name) { $e.name } else { $e.key }
  $proc = Get-JavaProc $e.procId $e.jar
  if (-not $proc) {
    Write-Host ('  - ' + $label + '（PID ' + $e.procId + '）已不在运行，清理记录') -ForegroundColor DarkGray
    continue
  }
  if ($DryRun) {
    Write-Host ('  [DryRun] 将停止 ' + $label + '（PID ' + $e.procId + '）') -ForegroundColor Yellow
    $kept++
    continue
  }
  try {
    Write-Host ('  + 停止 ' + $label + '（PID ' + $e.procId + '）...') -ForegroundColor Green
    Stop-Process -Id ([int]$e.procId) -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
      if ($null -eq (Get-Process -Id ([int]$e.procId) -ErrorAction SilentlyContinue)) { break }
      Start-Sleep -Milliseconds 300
    }
    if ($null -ne (Get-Process -Id ([int]$e.procId) -ErrorAction SilentlyContinue)) {
      Write-Host ('    [!] ' + $label + ' 未在 10s 内退出，强制结束') -ForegroundColor Yellow
      Stop-Process -Id ([int]$e.procId) -Force -ErrorAction SilentlyContinue
    }
    $stopped++
  } catch {
    Write-Host ('  [X] 停止 ' + $label + ' 失败: ' + $_.Exception.Message) -ForegroundColor Red
  }
}

if ($DryRun) {
  Write-Host ''
  Write-Host ('  DryRun 完成：将停止 ' + $kept + ' 个进程（未实际停止）') -ForegroundColor Yellow
  exit 0
}

# 清理状态文件（保留仍在运行的记录，通常为 0）
$remain = @()
foreach ($e in $entries) {
  if (-not $e -or -not $e.procId) { continue }
  if (Get-JavaProc $e.procId $e.jar) { $remain += $e }
}
if ($remain.Count -eq 0) {
  Remove-Item $stateFile -Force -ErrorAction SilentlyContinue
} else {
  ConvertTo-Json -InputObject $remain -Depth 6 | Set-Content $stateFile -Encoding UTF8
}

Write-Host ''
Write-Host ('  已停止 ' + $stopped + ' 个由本脚本启动的后端进程。') -ForegroundColor Cyan
Write-Host '  （IDEA / 手动启动的实例不受影响；Docker 容器未触碰）' -ForegroundColor DarkGray
Write-Host ''
exit 0
