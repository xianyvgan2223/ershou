﻿﻿﻿﻿﻿﻿﻿# =============================================================
# 三级缓存验收：Nginx L1 → Caffeine L2 → Redis L3
#
# 前置：
#   1) scripts\cloud-up.ps1 已启动 ershou-nginx（L1）
#   2) scripts\start-full-cloud.ps1 已启动网关与 item/map/platform
#   3) 仓库根 .env 存在（Redis 密码从这里读，不写死）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify-three-level-cache.ps1
#   ... -NginxBase http://127.0.0.1:8090 -GatewayBase http://127.0.0.1:8088
# =============================================================
param(
  [string]$NginxBase = 'http://127.0.0.1:8090',
  [string]$GatewayBase = 'http://127.0.0.1:8088',
  [long]$ItemId = 0,
  [string]$RedisContainer = 'ershou-redis',
  [int]$RedisDb = 2
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot

# ---------- .env ----------
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8) |
    Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Za-z_]+=' } |
    ForEach-Object {
      $line = $_.TrimStart([char]0xFEFF).Trim()
      $k, $v = (($line -split '#', 2)[0]) -split '=', 2
      if ($k -and $v) { Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim() }
    }
}

$pass = 0
$fail = 0
function Check([string]$desc, [bool]$cond, [string]$detail = '') {
  if ($cond) {
    $script:pass++
    Write-Host ('  [PASS] ' + $desc) -ForegroundColor Green
  } else {
    $script:fail++
    Write-Host ('  [FAIL] ' + $desc + '  ' + $detail) -ForegroundColor Red
  }
}

function Get-Headers([string]$url, [hashtable]$headers = @{}) {
  $curlArgs = @('-s', '-D', '-', '-o', 'NUL', $url)
  foreach ($k in $headers.Keys) { $curlArgs += @('-H', ($k + ': ' + $headers[$k])) }
  $raw = (& curl.exe @curlArgs 2>&1 | Out-String)
  $lines = $raw -split "`r?`n"
  $status = if ($lines.Count -gt 0) { $lines[0] } else { '' }
  $cache = ''
  foreach ($l in $lines) {
    if ($l -match '^X-Cache-Status:\s*(.*)$') { $cache = $Matches[1].Trim() }
  }
  return [pscustomobject]@{ Status = $status; Cache = $cache; Raw = $raw }
}

function Get-Json([string]$url) {
  try {
    return ((& curl.exe -s $url 2>&1 | Out-String) | ConvertFrom-Json)
  } catch {
    return $null
  }
}

Write-Host ''
Write-Host '=== 三级缓存验收 ===' -ForegroundColor Cyan

# 1) Nginx 健康
$health = (& curl.exe -s -o NUL -w '%{http_code}' ($NginxBase + '/nginx-health') 2>&1 | Out-String).Trim()
Check 'Nginx /nginx-health 返回 200' ($health -eq '200') ('HTTP ' + $health)

# 2) 商品列表 MISS → HIT → 过期
$cb = [guid]::NewGuid().ToString('N')
$listUrl = $NginxBase + '/api/v1/items?pageNum=1&pageSize=5&_cb=' + $cb
$h1 = Get-Headers $listUrl
$h2 = Get-Headers $listUrl
Check '商品列表首次 MISS' ($h1.Cache -eq 'MISS') ('X-Cache-Status=' + $h1.Cache + ' | ' + $h1.Status)
Check '商品列表第二次 HIT' ($h2.Cache -eq 'HIT') ('X-Cache-Status=' + $h2.Cache + ' | ' + $h2.Status)
Start-Sleep -Seconds 4
$h3 = Get-Headers $listUrl
Check '商品列表 TTL 到期后重新回源' ($h3.Cache -eq 'MISS' -or $h3.Cache -eq 'EXPIRED' -or $h3.Cache -eq 'STALE') ('X-Cache-Status=' + $h3.Cache)

# 3) 带登录态必须 BYPASS
$authHeaders = @{ Authorization = 'Bearer dummy-token-for-cache-test' }
$ha = Get-Headers $listUrl $authHeaders
Check '带 Authorization 的请求 BYPASS' ($ha.Cache -eq 'BYPASS') ('X-Cache-Status=' + $ha.Cache + ' | ' + $ha.Status)

# 4) /items/mine 永不 HIT
$mine = Get-Headers ($NginxBase + '/api/v1/items/mine') $authHeaders
Check '/items/mine 不命中共享缓存' ($mine.Cache -eq 'BYPASS' -or $mine.Cache -eq '' -or $mine.Cache -eq 'MISS') ('X-Cache-Status=' + $mine.Cache + ' | ' + $mine.Status)

# 5) 商品详情 MISS → HIT（自动从列表取一个 id）
if ($ItemId -le 0) {
  $list = Get-Json ($GatewayBase + '/api/v1/items?pageNum=1&pageSize=1')
  if ($list -and $list.data -and $list.data.records -and $list.data.records.Count -gt 0) {
    $ItemId = [long]$list.data.records[0].id
  }
}
if ($ItemId -gt 0) {
  $detailCb = [guid]::NewGuid().ToString('N')
  $detailUrl = $NginxBase + '/api/v1/items/' + $ItemId + '?_cb=' + $detailCb
  $d1 = Get-Headers $detailUrl
  $d2 = Get-Headers $detailUrl
  Check ('商品详情 ' + $ItemId + ' 首次 MISS') ($d1.Cache -eq 'MISS') ('X-Cache-Status=' + $d1.Cache + ' | ' + $d1.Status)
  Check ('商品详情 ' + $ItemId + ' 第二次 HIT') ($d2.Cache -eq 'HIT') ('X-Cache-Status=' + $d2.Cache + ' | ' + $d2.Status)
  $da = Get-Headers $detailUrl $authHeaders
  Check '商品详情带登录态 BYPASS' ($da.Cache -eq 'BYPASS') ('X-Cache-Status=' + $da.Cache)

  # 6) Redis L3 仍是权威缓存，TTL 应在 (0, 600]
  if ($env:REDIS_PASSWORD) {
    $ttlRaw = (& docker exec $RedisContainer redis-cli -a $env:REDIS_PASSWORD --no-auth-warning -n $RedisDb TTL ('ershou:item:detail:' + $ItemId) 2>&1 | Out-String).Trim()
    $ttl = 0
    if ([int]::TryParse($ttlRaw, [ref]$ttl)) {
      Check 'Redis 商品详情 TTL 在 (0,600]' ($ttl -gt 0 -and $ttl -le 600) ('TTL=' + $ttlRaw)
    } else {
      Check 'Redis 商品详情 TTL 可读取' $false ('输出=' + $ttlRaw)
    }
  } else {
    Write-Host '  [SKIP] .env 没有 REDIS_PASSWORD，跳过 Redis TTL 检查' -ForegroundColor Yellow
  }
} else {
  Write-Host '  [SKIP] 没有取到商品 id，跳过详情检查' -ForegroundColor Yellow
}

# 7) 分类 / 地图 / 平台公开读：只做 MISS → HIT 冒烟（接口不存在时跳过）
foreach ($path in @('/api/v1/categories', '/api/v1/map/points?campusId=2', '/api/v1/platform/shop/points?campusId=2')) {
  $u = $NginxBase + $path + $(if ($path.Contains('?')) { '&' } else { '?' }) + '_cb=' + [guid]::NewGuid().ToString('N')
  $c1 = Get-Headers $u
  if ($c1.Status -match ' 200') {
    $c2 = Get-Headers $u
    Check ($path + ' 首次 MISS') ($c1.Cache -eq 'MISS') ('X-Cache-Status=' + $c1.Cache)
    Check ($path + ' 第二次 HIT') ($c2.Cache -eq 'HIT') ('X-Cache-Status=' + $c2.Cache)
  } else {
    Write-Host ('  [SKIP] ' + $path + ' 非 200：' + $c1.Status) -ForegroundColor Yellow
  }
}

Write-Host ''
if ($fail -eq 0) {
  Write-Host ('验收通过：PASS ' + $pass + ' / FAIL 0') -ForegroundColor Green
  exit 0
} else {
  Write-Host ('验收失败：PASS ' + $pass + ' / FAIL ' + $fail) -ForegroundColor Red
  exit $fail
}
