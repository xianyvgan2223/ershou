﻿﻿﻿﻿﻿<#
.SYNOPSIS
  用 USB 数据线把手机连到本机后端（adb reverse 隧道），**不依赖 WiFi 同网段、也不怕 IP 变**。

.DESCRIPTION
  为什么需要它（2026-10-10 踩的坑）：

  手机在 `192.168.236.0/24`、电脑 WLAN 在 `10.33.56.248` —— **两者不在同一网段**，
  互相 ping 不通。而 App 里存的是上一次可用的 IP（`192.168.236.112`），
  于是打开地图就报"后端还没配置高德 key，暂时无法显示真实地图"。
  那句话**具有误导性**：后端其实配好了 key，只是手机根本连不上后端
  （`/amap-status` 拿不到 → App 回落到"未配置"的兜底文案）。

  用 adb reverse 走 USB 就绕开了网段/IP 问题：
  手机访问 `127.0.0.1:8080` 会被 adb 转发到电脑的 8080。

  三个端口都要转发：
    - 8080 后端 API
    - 9010 MinIO 图片（**不转发的话商品图会全部加载失败**）
    - 9011 MinIO 控制台（一般用不到，顺手转）
    - 8088 Spring Cloud 网关（可选；转发了才能把 App 地址填 `127.0.0.1:8088` 演示微服务）

  连好之后在 App 里：**连接页 → 一键扫描**（会识别出"USB 隧道"），或手动填 `127.0.0.1:8080`。

用法：
  powershell -ExecutionPolicy Bypass -File .\scripts\mobile-connect.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\mobile-connect.ps1 -Phone AQQB9X1526W02408
#>
param(
  [string]$Phone = 'AQQB9X1526W02408'
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Za-z_]+=' } | ForEach-Object {
    $line = $_.TrimStart([char]0xFEFF).Trim()
    $k, $v = (($line -split '#', 2)[0]) -split '=', 2
    if ($k -and $v) { Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim() }
  }
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$adb = 'D:\tools\Android\Sdk\platform-tools\adb.exe'

if (-not (Test-Path $adb)) {
  Write-Host "找不到 adb：$adb" -ForegroundColor Red
  exit 1
}
$devices = & $adb devices 2>&1 | Select-String -Pattern "$Phone\s+device"
if (-not $devices) {
  Write-Host "设备 $Phone 未连接（adb devices 里没看到）。请插上 USB 并确认已授权调试。" -ForegroundColor Red
  & $adb devices
  exit 1
}
Write-Host "设备 $Phone 已连接" -ForegroundColor Green

# ---------- 1. 建隧道 ----------
Write-Host ''
Write-Host '建立 USB 隧道（手机 → 本机）...' -ForegroundColor Cyan
& $adb reverse --remove-all 2>&1 | Out-Null
foreach ($p in 8080, 8088, 9010, 9011) {
  & $adb reverse "tcp:$p" "tcp:$p" 2>&1 | Out-Null
}
$list = & $adb reverse --list 2>&1 | Where-Object { $_ -match 'tcp:\d+' }
Write-Host ("  已建立 " + ($list | Measure-Object).Count + " 条：" + (($list | ForEach-Object { ($_ -replace '.*tcp:(\d+).*', '$1') }) -join ', '))

# ---------- 2. 从手机侧实测 ----------
Write-Host ''
Write-Host '从手机侧验证...' -ForegroundColor Cyan
$ok = $true
$apiCode = (& $adb shell "curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/v1/map/amap-status" 2>&1) -join ''
if ($apiCode.Trim() -eq '200') {
  Write-Host '  ✓ 后端 API（8080）可达' -ForegroundColor Green
} else {
  Write-Host ("  ✗ 后端 API（8080）不可达，HTTP=" + $apiCode + " —— 后端起了吗？") -ForegroundColor Red
  $ok = $false
}

# 可选：Spring Cloud 网关（8088）——只在本地网关已启动时验证
$gwLocal = Test-NetConnection -ComputerName 127.0.0.1 -Port 8088 -InformationLevel Quiet -WarningAction SilentlyContinue
if ($gwLocal) {
  $gwCode = (& $adb shell "curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/v1/items" 2>&1) -join ''
  if ($gwCode.Trim() -eq '200') {
    Write-Host '  ✓ Spring Cloud 网关（8088）可达 —— App 地址填 127.0.0.1:8088 即可演示微服务' -ForegroundColor Green
  } else {
    Write-Host ("  · Spring Cloud 网关（8088）手机侧 HTTP=" + $gwCode + "（网关可能未起/正在启动，跳过）") -ForegroundColor DarkGray
  }
} else {
  Write-Host '  · Spring Cloud 网关（8088）未启动，跳过（隧道已预留，起了网关即可用）' -ForegroundColor DarkGray
}

# 取一张真实图片验证 MinIO 隧道（403 是 MinIO 根路径的正常响应，要测具体对象）
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$img = ''
if (Test-Path $mysql) {
  $img = (& $mysql -h 127.0.0.1 -P 3306 -u ershou -N -B -e "use ershou; select cover_url from item where status=2 and cover_url like 'http%' limit 1" 2>$null | Out-String).Trim()
}
if ($img) {
  $path = ([uri]$img).PathAndQuery
  $imgCode = (& $adb shell "curl -s -m 6 -o /dev/null -w '%{http_code}' http://127.0.0.1:9010$path" 2>&1) -join ''
  if ($imgCode.Trim() -eq '200') {
    Write-Host '  ✓ MinIO 图片（9010）可达 —— 商品图能正常加载' -ForegroundColor Green
  } else {
    Write-Host ("  ✗ MinIO 图片（9010）不可达，HTTP=" + $imgCode) -ForegroundColor Red
    $ok = $false
  }
} else {
  Write-Host '  （库里没有带图的在售商品，跳过图片验证）' -ForegroundColor DarkGray
}

# ---------- 3. 顺带解释"为什么 WiFi 连不上" ----------
Write-Host ''
$wlan = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object { $_.InterfaceAlias -eq 'WLAN' -and $_.IPAddress -notlike '169.254.*' } |
  Select-Object -First 1
$phoneNet = (& $adb shell "ip route" 2>&1 | Select-String -Pattern 'wlan0' | Select-Object -First 1)
if ($wlan) {
  Write-Host ("电脑 WLAN = " + $wlan.IPAddress) -ForegroundColor DarkGray
}
if ($phoneNet) {
  Write-Host ("手机网段 = " + ($phoneNet.Line -replace '\s+', ' ').Trim()) -ForegroundColor DarkGray
}
if ($wlan -and $phoneNet -and $phoneNet.Line -notmatch [regex]::Escape(($wlan.IPAddress -replace '\.\d+$', '.'))) {
  Write-Host ''
  Write-Host '⚠ 手机和电脑**不在同一网段**，所以 WiFi 直连（填电脑 IP）是走不通的。' -ForegroundColor Yellow
  Write-Host '  要么把两台设备连到同一个 WiFi，要么就用这条 USB 隧道。' -ForegroundColor Yellow
}

# ---------- 4. 告诉用户下一步 ----------
Write-Host ''
if ($ok) {
  Write-Host '隧道就绪。在 App 里这样连：' -ForegroundColor Green
  Write-Host '  连接页 → 点「一键扫描」→ 选带「USB 隧道」标记的那个；或手动填 127.0.0.1:8080' -ForegroundColor Green
} else {
  Write-Host '隧道建好了，但验证没通过 —— 先确认后端（8080）和 MinIO（9010）都在跑。' -ForegroundColor Yellow
}
Write-Host ''
Write-Host '注意：**拔掉 USB 或重启 adb 后隧道会失效**，重新跑一次本脚本即可。' -ForegroundColor DarkGray
