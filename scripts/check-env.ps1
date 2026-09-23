<#
.SYNOPSIS
  校园二手交易平台 · 开发环境一键自检脚本
.DESCRIPTION
  检查 JDK / Maven / Node / MySQL / Redis / Git / Docker 是否就绪，并给出安装建议。
  运行： powershell -ExecutionPolicy Bypass -File .\scripts\check-env.ps1
#>
$ErrorActionPreference = 'SilentlyContinue'
$results = @()
$NL = [Environment]::NewLine

function Test-Cmd($cmd, $args) {
  if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { return $null }
  try { return ((& $cmd $args 2>&1 | Out-String).Trim() -split $NL)[0].Trim() } catch { return 'FAILED' }
}
function Add-Result($item, $ok, $detail, $fix) {
  $script:results += [pscustomobject]@{ Item = $item; State = $(if ($ok) { 'OK' } else { 'MISSING' }); Detail = $detail; Fix = $fix }
}

Write-Host ($NL + '===== 校园二手交易平台 · 环境自检 =====') -ForegroundColor Cyan
Write-Host ('时间: ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + '   主机: ' + $env:COMPUTERNAME + $NL)

# JDK
$javaHome = $env:JAVA_HOME
$javaVer = Test-Cmd 'java' '-version'
$hasJavac = $javaHome -and (Test-Path (Join-Path $javaHome 'bin\javac.exe'))
if ($hasJavac) { Add-Result 'JDK 17+' $true ('JAVA_HOME=' + $javaHome + ' | ' + $javaVer) '-' }
elseif ($javaVer) {
  if ($javaVer -match '1\.8') { Add-Result 'JDK 17+' $false ('检测到 Java 8: ' + $javaVer) '装 JDK 21 并设置 JAVA_HOME（Spring Boot 3 需 17+）' }
  else { Add-Result 'JDK 17+' $true $javaVer '建议把 JAVA_HOME 指向 JDK 21' }
} else { Add-Result 'JDK 17+' $false '未检测到 java' 'winget install EclipseAdoptium.Temurin.21.JDK' }

# Maven
$mvn = Test-Cmd 'mvn' '-v'
if ($mvn) { Add-Result 'Maven 3.9+' $true $mvn '-' }
elseif (Test-Path '.\mvnw.cmd') { Add-Result 'Maven 3.9+' $true '未全局安装，但项目内有 mvnw（用 .\mvnw.cmd 即可）' '-' }
else { Add-Result 'Maven 3.9+' $false '未检测到 mvn' '① winget install Apache.Maven ② 用 IDEA 内置 Maven ③ 项目用 mvnw（推荐）' }

# Node
$node = Test-Cmd 'node' '-v'
$npm = Test-Cmd 'npm' '-v'
$pnpm = Test-Cmd 'pnpm' '-v'
if ($node) {
  $ok = $node -match '^v(1[89]|2[0-9])'
  Add-Result 'Node.js 18+' $ok ('node ' + $node + ' | npm ' + $npm) $(if ($ok) { '-' } else { '升级: winget install OpenJS.NodeJS.LTS' })
} else { Add-Result 'Node.js 18+' $false '未检测到 node' 'winget install OpenJS.NodeJS.LTS' }
Add-Result 'pnpm (可选)' ([bool]$pnpm) $(if ($pnpm) { $pnpm } else { '未安装（用 npm 也行）' }) 'corepack enable ; 或 npm i -g pnpm'

# MySQL
$mysqlCli = Test-Cmd 'mysql' '--version'
$mysqlSvc = (Get-Service | Where-Object { $_.Name -match '^MySQL' } | ForEach-Object { $_.Name + '=' + $_.Status }) -join ', '
$p3306 = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -InformationLevel Quiet -WarningAction SilentlyContinue
if ($mysqlCli) { Add-Result 'MySQL 8.0' $true ($mysqlCli + ' | 服务: ' + $mysqlSvc + ' | 3306: ' + $p3306) $(if ($p3306) { '-' } else { '启动服务: net start MySQL80' }) }
else { Add-Result 'MySQL 8.0' $false ('未检测到 mysql 客户端 | 服务: ' + $mysqlSvc) 'winget install Oracle.MySQL ；或 docker compose up -d mysql' }

# Redis
$redisSrv = Test-Cmd 'redis-server' '--version'
$p6379 = Test-NetConnection -ComputerName 127.0.0.1 -Port 6379 -InformationLevel Quiet -WarningAction SilentlyContinue
if ($redisSrv) { Add-Result 'Redis 7.x' $true ($redisSrv + ' | 6379: ' + $p6379) $(if ($p6379) { '-' } else { '启动 redis-server' }) }
else { Add-Result 'Redis 7.x' $false ('未检测到 redis-server | 6379: ' + $p6379) 'Windows 无官方版: ① docker compose up -d redis(推荐) ② WSL2 apt install redis-server ③ Memurai' }

# Git / Docker
$git = Test-Cmd 'git' '--version'
Add-Result 'Git' ([bool]$git) $(if ($git) { $git } else { '未检测到 git' }) 'winget install Git.Git'
$docker = Test-Cmd 'docker' '-v'
$dockerSvc = (Get-Service 'com.docker.service').Status
Add-Result 'Docker (可选)' ([bool]$docker) $(if ($docker) { $docker + ' | 服务=' + $dockerSvc } else { '未检测到 docker（服务=' + $dockerSvc + '）' }) '启动 Docker Desktop；未装: winget install Docker.DockerDesktop'

# 端口
$ports = 3306, 6379, 8080, 5173, 80
$used = foreach ($p in $ports) {
  $c = Get-NetTCPConnection -State Listen -LocalPort $p | Select-Object -First 1
  if ($c) { $p.ToString() + '(' + (Get-Process -Id $c.OwningProcess).ProcessName + ')' }
}
Write-Host ('端口占用: ' + $(if ($used) { $used -join ', ' } else { '无' })) -ForegroundColor DarkGray

Write-Host ($NL + '===== 自检结果 =====') -ForegroundColor Cyan
$results | Format-Table -AutoSize -Wrap | Out-String -Width 200 | Write-Host
$missing = @($results | Where-Object { $_.State -eq 'MISSING' })
if ($missing.Count -eq 0) { Write-Host '全部就绪，可以开始开发！' -ForegroundColor Green }
else {
  Write-Host ('还差 ' + $missing.Count + ' 项需要处理：') -ForegroundColor Yellow
  foreach ($m in $missing) { Write-Host ('  - ' + $m.Item + '  ==>  ' + $m.Fix) -ForegroundColor Yellow }
}
Write-Host ''
