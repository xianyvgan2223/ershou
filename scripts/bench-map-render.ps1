# =============================================================
# 地图页真实渲染耗时实测（前端侧）
#
# 后端接口再快，浏览器要为每个点位建一个自定义 marker（+常驻标签），
# 这里就在花江校区造出真实点位，用无头 Chrome 打开 /map，读页面埋点
# （AmapCampusMap 会把 MARKERS_DONE n=… ms=… 写进 document.title）。
#
# 隔离：测试实例 8081 + Redis 1 号库（数据在 campus 2 = 花江）
#      另起 dev server 5199 并代理到 8081（用户的 5173 代理到 8080，会测错实例）
# 用法： powershell -ExecutionPolicy Bypass -File .\scripts\bench-map-render.ps1
# =============================================================
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$base = 'http://127.0.0.1:8081/api/v1'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$chrome = 'C:\Program Files\Google\Chrome\Application\chrome.exe'

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

function Sql1([string]$q) { (& $mysql -h 127.0.0.1 -P 3306 -u ershou -D ershou -N -B -e $q 2>$null) -join '' }

function SeedHuajiang([int]$n) {
  # 过程体里有分号：必须用 DELIMITER 包起来，否则 mysql 客户端在第一个分号处就截断了
  $proc = @"
DROP PROCEDURE IF EXISTS bench_hj_seed;
DELIMITER `$`$
CREATE PROCEDURE bench_hj_seed(IN p_points INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  WHILE i < p_points DO
    INSERT INTO trade_point (id, campus_id, name, source, point_type, lng, lat, safety_score, safe_tags, guide_text, status, audit_status)
    VALUES (800000 + i, 2, CONCAT('渲染压测点-', i), 1, 3,
            110.410000 + (i % 60) * 0.0006, 25.305000 + FLOOR(i / 60) * 0.0006,
            70, '["监控"]', CONCAT('渲染压测 ', i), 1, 1);
    SET i = i + 1;
  END WHILE;
END`$`$
DELIMITER ;
CALL bench_hj_seed($n);
"@
  $tmp = Join-Path $root '.build\bench-hj-proc.sql'
  [System.IO.File]::WriteAllText($tmp, $proc, (New-Object System.Text.UTF8Encoding($true)))
  cmd /c "`"$mysql`" --host=127.0.0.1 --port=3306 --user=ershou --password=$($env:MYSQL_PASSWORD) --default-character-set=utf8mb4 --database=ershou < `"$tmp`" 2>&1" | Out-Null
}

function ClearCache() {
  docker exec ershou-redis redis-cli -a $env:REDIS_PASSWORD --no-auth-warning -n 1 eval "for i=1,#ARGV do local ks=redis.call('keys',ARGV[i]); for _,k in ipairs(ks) do redis.call('del',k) end end return 'ok'" 0 'ershou:map:*' 'ershou:dict:*' 2>&1 | Out-Null
}

Write-Host '=== 起测试实例（8081 / redis db1，数据在 campus 2 = 花江） ===' -ForegroundColor Cyan
$proc = Start-Process -FilePath $mvn -ArgumentList '-B', '-o', 'spring-boot:run', '-Dspring-boot.run.profiles=dev,local' `
  -WorkingDirectory (Join-Path $root 'ershou-server') -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\bench-render-server.log') `
  -RedirectStandardError (Join-Path $root '.build\bench-render-server.err.log')
for ($i = 1; $i -le 90; $i++) {
  Start-Sleep -Seconds 2
  try { $p = Invoke-RestMethod -Uri "$base/trade-points?campusId=2" -TimeoutSec 5; if ($null -ne $p.code) { break } } catch { }
}
Write-Host '  就绪' -ForegroundColor Green

# 用户的 dev server（5173）把 /api 代理到 8080，压测数据在 8081，
# 所以另起 5199 指向 8081，避免测到错误的实例。
Write-Host '=== 起压测用 dev server（5199 → 代理到 8081） ===' -ForegroundColor Cyan
$devCmd = "Set-Location 'E:\ershou\ershou-web'; `$env:VITE_PROXY_TARGET='http://127.0.0.1:8081'; npm run dev -- --port 5199 --strictPort"
$dev = Start-Process -FilePath 'powershell' -ArgumentList '-NoProfile', '-Command', $devCmd `
  -PassThru -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $root '.build\bench-dev.log') `
  -RedirectStandardError (Join-Path $root '.build\bench-dev.err.log')
$devReady = $false
for ($i = 1; $i -le 40; $i++) {
  Start-Sleep -Seconds 1
  try { $r = Invoke-WebRequest -Uri 'http://localhost:5199/' -UseBasicParsing -TimeoutSec 3; if ($r.StatusCode -eq 200) { $devReady = $true; break } } catch { }
}
if ($devReady) {
  $probeKb = [math]::Round((Invoke-WebRequest -Uri 'http://localhost:5199/api/v1/map/points?campusId=2' -UseBasicParsing -TimeoutSec 10).RawContentLength / 1024, 1)
  Write-Host ("  5199 就绪，代理自检：/api/v1/map/points 返回 " + $probeKb + " KB") -ForegroundColor Green
} else {
  Write-Host '  5199 起不来：页面探针会打到 8080，数据不准' -ForegroundColor Red
}

try {
  foreach ($n in 50, 300, 800) {
    Sql1 "DELETE FROM trade_point WHERE id BETWEEN 800000 AND 899999;" | Out-Null
    SeedHuajiang $n
    ClearCache
    $cnt = Sql1 "SELECT COUNT(*) FROM trade_point WHERE campus_id=2;"
    $jsonKb = [math]::Round((Invoke-WebRequest -Uri "$base/map/points?campusId=2" -UseBasicParsing).RawContentLength / 1024, 1)

    $profile = Join-Path $root ".build\chrome-render-$n"
    New-Item -ItemType Directory -Force -Path $profile | Out-Null
    $hist = Join-Path $profile 'Default\History'
    Remove-Item -Force $hist -ErrorAction SilentlyContinue
    & $chrome --headless=new --disable-gpu --no-first-run "--user-data-dir=$profile" `
      --window-size=1400,900 --virtual-time-budget=45000 `
      "http://localhost:5199/map" 2>$null
    Start-Sleep -Seconds 2

    $rendered = ''
    if (Test-Path $hist) {
      Copy-Item $hist (Join-Path $root '.build\h.db') -Force
      $txt = [System.IO.File]::ReadAllText((Join-Path $root '.build\h.db'), [System.Text.Encoding]::UTF8)
      $m = [regex]::Match($txt, 'MARKERS_DONE[^|"]{0,140}')
      if ($m.Success) { $rendered = ($m.Value -replace '[^\x20-\x7E].*$', '') }
    }
    Write-Host ("点位 " + $cnt + " 个（JSON " + $jsonKb + " KB）→ " + ($(if ($rendered) { $rendered } else { '（未取到页面探针结果）' })))
  }
}
finally {
  if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
  if ($dev -and -not $dev.HasExited) { Stop-Process -Id $dev.Id -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  foreach ($port in 8081, 5199) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($c) { Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue }
  }
  Sql1 "DELETE FROM trade_point WHERE id BETWEEN 800000 AND 899999; DROP PROCEDURE IF EXISTS bench_hj_seed;" | Out-Null
  ClearCache
  Write-Host ("  清理完成，花江剩余点位 = " + (Sql1 "SELECT COUNT(*) FROM trade_point WHERE campus_id=2;")) -ForegroundColor DarkGray
  Write-Host '  测试实例与压测 dev server 已关闭' -ForegroundColor DarkGray
}
