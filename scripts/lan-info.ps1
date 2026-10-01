<#
.SYNOPSIS
  打印手机端要连的局域网地址（校园网 IP 会变，每次换网跑一下）
.DESCRIPTION
  只读操作：列出所有非虚拟网卡的 IPv4、推断手机可用的地址，并实测 /api/v1/categories。
  用法： powershell -ExecutionPolicy Bypass -File .\scripts\lan-info.ps1
#>
$ErrorActionPreference = 'Continue'

Write-Host '=== 本机 IPv4 网卡 ===' -ForegroundColor Cyan
$addresses = Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
  $_.IPAddress -notmatch '^127\.' -and
  $_.IPAddress -notmatch '^169\.254\.' -and
  $_.InterfaceAlias -notmatch 'Loopback|vEthernet|VMware|Radmin'
}
$addresses | Select-Object InterfaceAlias, IPAddress, PrefixLength | Format-Table -AutoSize

$primary = $addresses | Where-Object { $_.InterfaceAlias -match 'WLAN|Wi-Fi|以太网|Ethernet' } | Select-Object -First 1
if ($null -eq $primary) { $primary = $addresses | Select-Object -First 1 }

if ($primary) {
  $host_ = $primary.IPAddress
  Write-Host ('手机端填这个地址： ' + $host_ + ':8080') -ForegroundColor Green
  Write-Host ('完整探测地址：   http://' + $host_ + ':8080/api/v1/categories') -ForegroundColor DarkGray
  try {
    $resp = Invoke-WebRequest -Uri ('http://' + $host_ + ':8080/api/v1/categories') -TimeoutSec 5 -UseBasicParsing
    if ($resp.StatusCode -eq 200) {
      Write-Host '本机自测：后端在 0.0.0.0:8080 上可访问（手机应也能连）' -ForegroundColor Green
    }
  } catch {
    Write-Host ('本机自测失败：' + $_.Exception.Message) -ForegroundColor Yellow
    Write-Host '  后端没启动？ 先跑 .\scripts\dev-server.ps1' -ForegroundColor Yellow
  }
} else {
  Write-Host '没有找到可用的局域网网卡' -ForegroundColor Red
}

Write-Host ''
Write-Host '手机连不上时的检查顺序：' -ForegroundColor Cyan
Write-Host '  1) 手机和电脑连同一个 WiFi（校园网若有 AP 隔离，改用手机热点让电脑连）'
Write-Host '  2) 电脑防火墙放行 8080（TCP）与 48899（UDP，一键扫描用）'
Write-Host '  3) App 里点「一键扫描」，或手动填上面的地址'
