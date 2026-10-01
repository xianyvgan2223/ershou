<#
.SYNOPSIS
  启动后端（Spring Boot 3 + JDK 21）
.DESCRIPTION
  本机没有全局 Maven，脚本会自动使用 IDEA 自带的 Maven。
  前置： MySQL 服务已启动、Redis/MinIO 容器已启动（先跑 .\scripts\dev-up.ps1）
  用法： powershell -ExecutionPolicy Bypass -File .\scripts\dev-server.ps1
#>
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$serverDir = Join-Path $root 'ershou-server'
Set-Location $serverDir

# JDK 21
$jdk = 'C:\Program Files\Java\jdk-21'
if (Test-Path $jdk) {
  $env:JAVA_HOME = $jdk
  $env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
  Write-Host ('JAVA_HOME = ' + $jdk)
} else {
  Write-Host '未找到 C:\Program Files\Java\jdk-21，请确认 JDK 安装路径' -ForegroundColor Yellow
}

# Maven：优先用 IDEA 自带的
$mvnCandidates = @(
  'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd',
  'D:\IntelliJ IDEA 2024.3.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
$mvn = $null
foreach ($c in $mvnCandidates) { if (Test-Path $c) { $mvn = $c; break } }
if ($null -eq $mvn) {
  $cmd = Get-Command mvn -ErrorAction SilentlyContinue
  if ($cmd) { $mvn = $cmd.Source }
}
if ($null -eq $mvn) {
  Write-Host '未找到 Maven（也没有全局 mvn），请在 IDEA 里直接运行 CampusApplication' -ForegroundColor Red
  exit 1
}
Write-Host ('使用 Maven: ' + $mvn)

# 载入 .env（供 application-dev.yml 的占位符使用；也可用 local profile 覆盖）
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
    $_ = $_.TrimStart([char]0xFEFF).Trim()
    # .env 里有的变量把注释写在同一行（例：`# 说明... VITE_AMAP_JS_KEY=xxx`），
    # 直接 split 会把 key 读成注释文本，所以先砍掉 # 之后的内容。
    $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
    if (-not $k -or -not $v) { continue }
    Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
  }
  Write-Host '已载入 .env'
}

Write-Host ''
Write-Host '启动后端 http://127.0.0.1:8080   （接口文档 http://127.0.0.1:8080/swagger-ui.html）' -ForegroundColor Green
Write-Host '按 Ctrl+C 停止' -ForegroundColor DarkGray
Write-Host ''
& $mvn -B spring-boot:run "-Dspring-boot.run.profiles=dev,local"
