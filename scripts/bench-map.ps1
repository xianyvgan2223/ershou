# =============================================================
# 校内地图容量压测（多量级扫一遍，找拐点）
#
# 回答：点位/商品多到什么程度，地图页会开始难受？
# 测的量级：200 / 1000 / 5000 / 10000 个面交点，在售商品数 = 点位数 × 20
#
# 隔离：测试实例跑 8081 + Redis 1 号库，数据挂 campus_id=9，跑完清理
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\bench-map.ps1
# =============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8081/api/v1'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

[System.IO.File]::ReadAllLines((Join-Path $root '.env'), [System.Text.Encoding]::UTF8) | Where-Object { ($_ -split '#', 2)[0] -match '^\s*[A-Z_]+=' } | ForEach-Object {
  $_ = $_.TrimStart([char]0xFEFF).Trim()
  # .env 里有的变量把注释写在同一行（例：`# 说明... VITE_AMAP_JS_KEY=xxx`），
  # 直接 split 会把 key 读成注释文本，所以先砍掉 # 之后的内容。
  $k, $v = ((($_ -split '#', 2)[0]).TrimStart([char]0xFEFF)) -split '=', 2
  if (-not $k -or -not $v) { continue }
  Set-Item -Path ('env:' + $k.Trim()) -Value $v.Trim()
}
$env:MYSQL_PWD = $env:MYSQL_PASSWORD
$env:SERVER_PORT = '8081'
$env:REDIS_DATABASE = '1'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = 'C:\Program Files\Java\jdk-21\bin;' + $env:Path
$mvn = 'D:\tools\IntelliJ IDEA 2025.1\plugins\maven\lib\maven3\bin\mvn.cmd'

function SqlFile([string]$path) {
  cmd /c "`"$mysql`" --host=127.0.0.1 --port=3306 --user=ershou --password=$($env:MYSQL_PASSWORD) --default-character-set=utf8mb4 --database=ershou < `"$path`" 2>&1" | Out-Null
}
function Sql1([string]$q) {
  (& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join ''
}
function TimeIt([string]$url) {
  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  $resp = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 300
  $sw.Stop()
  @{ ms = $sw.ElapsedMilliseconds; kb = [math]::Round($resp.RawContentLength / 1024, 1) }
}
function ClearMapCache() {
  docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning -n 1 eval "for i=1,#ARGV do local ks=redis.call('keys',ARGV[i]); for _,k in ipairs(ks) do redis.call('del',k) end end return 'ok'" 0 'ershou:map:*' 'ershou:dict:*' 2>&1 | Out-Null
}

SqlFile (Join-Path $root 'sql\bench-map-proc.sql')

Write-Host '=== 起测试实例（8081 / redis db1） ===' -ForegroundColor Cyan
$logFile = Join-Path $root '.build\bench-server.log'
$proc = Start-Process -FilePath $mvn -ArgumentList '-B', '-o', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $root '.build\bench-server.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/trade-points?campusId=9" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '  就绪' -ForegroundColor Green

$results = @()
try {
  foreach ($points in 200, 1000, 5000, 10000) {
    $items = $points * 20
    Write-Host ''
    Write-Host ("=== 量级：点位 " + $points + " / 在售商品 " + $items + " ===") -ForegroundColor Cyan
    Sql1 "DELETE FROM item WHERE campus_id=9; DELETE FROM trade_point WHERE campus_id=9;" | Out-Null
    $seed = [System.Diagnostics.Stopwatch]::StartNew()
    Sql1 "CALL bench_map_seed($points, $items);" | Out-Null
    $seed.Stop()
    $actual = Sql1 "SELECT CONCAT(COUNT(*)) FROM trade_point WHERE campus_id=9;"
    Write-Host ("  造数 " + [math]::Round($seed.Elapsed.TotalSeconds, 1) + " 秒，实际点位 " + $actual) -ForegroundColor DarkGray

    ClearMapCache
    $cold = TimeIt "$base/map/points?campusId=9"
    $warm = TimeIt "$base/map/points?campusId=9"
    Write-Host ("  /map/points  冷(现算聚合) " + $cold.ms + " ms / 热(缓存) " + $warm.ms + " ms，JSON " + $warm.kb + " KB")

    $list = TimeIt "$base/trade-points?campusId=9"
    Write-Host ("  /trade-points " + $list.ms + " ms，" + $list.kb + " KB")

    $results += [pscustomobject]@{
      点位数       = $points
      在售商品     = $items
      冷耗时ms     = $cold.ms
      热耗时ms     = $warm.ms
      'JSON_KB'    = $warm.kb
      'DOM节点估算' = $points * 4
    }
  }
}
finally {
  Write-Host ''
  Write-Host '=== 汇总 ===' -ForegroundColor Cyan
  $results | Format-Table -AutoSize
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $c = Get-NetTCPConnection -State Listen -LocalPort 8081 -ErrorAction SilentlyContinue
  if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  Sql1 "DELETE FROM item WHERE campus_id=9; DELETE FROM trade_point WHERE campus_id=9; DELETE FROM campus WHERE id=9; DROP PROCEDURE IF EXISTS bench_map_seed;" | Out-Null
  ClearMapCache
  Write-Host ("  清理完成，剩余压测数据 = " + (Sql1 "SELECT CONCAT((SELECT COUNT(*) FROM trade_point WHERE campus_id=9), '/', (SELECT COUNT(*) FROM item WHERE campus_id=9));")) -ForegroundColor DarkGray
  Write-Host '  测试实例已关闭' -ForegroundColor DarkGray
}
